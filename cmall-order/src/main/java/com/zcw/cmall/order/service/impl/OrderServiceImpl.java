package com.zcw.cmall.order.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.zcw.cmall.order.constant.OrderConstant;
import com.zcw.cmall.order.dao.OrderItemDao;
import com.zcw.cmall.order.entity.OrderItemEntity;
import com.zcw.cmall.order.entity.PaymentInfoEntity;
import com.zcw.cmall.order.enume.OrderStatusEnum;
import com.zcw.cmall.order.feign.CartFeignService;
import com.zcw.cmall.order.feign.GoodsFeignService;
import com.zcw.cmall.order.feign.MemberFeignService;
import com.zcw.cmall.order.feign.StockFeignService;
import com.zcw.cmall.order.interceptor.LoginUserInterceptor;
import com.zcw.cmall.order.service.OrderItemService;
import com.zcw.cmall.order.service.PaymentInfoService;
import com.zcw.cmall.order.to.OrderCreateTo;
import com.zcw.cmall.order.vo.*;
import com.zcw.common.exception.NoStockException;
import com.zcw.common.to.OrderCloseTo;
import com.zcw.common.utils.R;
import com.zcw.common.vo.MemberRespVo;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcw.common.utils.PageUtils;
import com.zcw.common.utils.Query;

import com.zcw.cmall.order.dao.OrderDao;
import com.zcw.cmall.order.entity.OrderEntity;
import com.zcw.cmall.order.service.OrderService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;


@Service("orderService")
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {


    private ThreadLocal<OrderSubmitVo> OrderSubmitVoThreadLocal = new ThreadLocal<>();

    @Autowired
    PaymentInfoService paymentInfoService;
    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    OrderItemService orderItemService;
    @Autowired
    OrderItemDao orderItemDao;
    @Autowired
    GoodsFeignService goodsFeignService;
    @Autowired
    StringRedisTemplate redisTemplate;
    @Autowired
    StockFeignService stockFeignService;
    @Autowired
    ThreadPoolExecutor threadPoolExecutor;
    @Autowired
    CartFeignService cartFeignService;
    @Autowired
    MemberFeignService memberFeignService;
    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>()
        );

        return new PageUtils(page);
    }

    /**
     * ???????????????
     * @return
     */
    @Override
    public OrderConfirmVo confirmOrder() throws ExecutionException, InterruptedException {
        OrderConfirmVo vo = new OrderConfirmVo();
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();

        //?????????????????????ThreadLocal??????????????????????????????ThreadLocal
        //???????????????????????????RequestAttributes??????????????????????????????
        // RequestContextHolder???request?????????????????????ThreadLocal???????????????
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        CompletableFuture<Void> getAddressFuture = CompletableFuture.runAsync(() -> {
            RequestContextHolder.setRequestAttributes(requestAttributes);
            //1.??????????????????????????????
            List<MemberAddressVo> address = memberFeignService.getAddress(memberRespVo.getId());
            vo.setMemberAddressVos(address);
        }, threadPoolExecutor);

        CompletableFuture<Void> cartFuture = CompletableFuture.runAsync(() -> {
            RequestContextHolder.setRequestAttributes(requestAttributes);
            //2.????????????????????????????????????????????????
            //??????????????????????????????????????????feign??????????????????????????????template?????????????????????request?????????header????????????????????????
            //???????????????feign??????????????????cookie??????header???
            List<OrderItemVo> currentUserCartItems = cartFeignService.getCurrentUserCartItems();
            vo.setItems(currentUserCartItems);
        }, threadPoolExecutor).thenRunAsync(() -> {
            List<OrderItemVo> items = vo.getItems();
            //?????????????????????????????????
            List<Long> skuIds = items.stream().map(item -> item.getSkuId()).collect(Collectors.toList());
            R skusHasStock = stockFeignService.getSkusHasStock(skuIds);
            List<SkuStockVo> data = skusHasStock.getData(new TypeReference<List<SkuStockVo>>() {});
            if (data!=null){
                Map<Long, Boolean> map = data.stream().collect(Collectors.toMap(SkuStockVo::getSkuId, SkuStockVo::getHasStock));
                vo.setStocks(map);
            }
        },threadPoolExecutor);

        //3.??????????????????
        Integer integration = memberRespVo.getIntegration();
        vo.setIntegration(integration);


        //????????????

        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(OrderConstant.USER_ORDER_TOKEN_PREFIX+memberRespVo.getId(),token,30, TimeUnit.MINUTES);
        vo.setOrderToken(token);
        CompletableFuture.allOf(getAddressFuture,cartFuture).get();
        return vo;
    }

    /**
     * ??????
     * @param vo ??????????????????????????? to ?????????????????????????????????
     * @return
     */
    //@GlobalTransactional ??????Seata????????????????????????????????????????????????????????????
    @Transactional
    @Override
    public OrderSubmitRespVo submitOrder(OrderSubmitVo vo) {
        //?????????????????????????????????????????????
        OrderSubmitVoThreadLocal.set(vo);
        //??????????????????
        OrderSubmitRespVo respVo = new OrderSubmitRespVo();
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
        respVo.setCode(0);
        //????????????[?????????????????????????????????
        String script= "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        String orderToken = vo.getOrderToken();
        //0 ??????  1 ??????
        Long result = redisTemplate.execute(new DefaultRedisScript<Long>(script, Long.class), Arrays.asList(OrderConstant.USER_ORDER_TOKEN_PREFIX + memberRespVo.getId()), orderToken);
        if (result == 0L){
            //0??????  ???????????????
            respVo.setCode(1);
            return respVo;
        }else{
            //??????
            OrderCreateTo order = createOrder();
            BigDecimal payAmount = order.getOrder().getPayAmount();
            BigDecimal payPrice = vo.getPayPrice();
            if (Math.abs(payAmount.subtract(payPrice).doubleValue())<0.01){
                //??????????????????
                //????????????????????????
                saveOrder(order);
                //????????????,????????????????????????????????????????????????????????????????????????????????????@Transactional?????????????????????
                //???????????????????????????????????????????????????
                StockSkuLockVo lockVo = new StockSkuLockVo();
                lockVo.setOrderSn(order.getOrder().getOrderSn());
                List<OrderItemVo> locks = order.getOrderItems().stream().map(item -> {
                    OrderItemVo orderItemVo = new OrderItemVo();
                    orderItemVo.setSkuId(item.getSkuId());
                    orderItemVo.setCount(item.getSkuQuantity());
                    orderItemVo.setTitle(item.getSkuName());
                    return orderItemVo;
                }).collect(Collectors.toList());
                lockVo.setLocks(locks);
                R r = stockFeignService.orderLockStock(lockVo);

                if (r.getCode() == 0){
                    //????????????
                    respVo.setOrder(order.getOrder());
//                    int i= 10/0;
                    //????????????????????????MQ??????????????????                                                                  ?????????????????????order.getOrder()?????????????????????
                    rabbitTemplate.convertAndSend("order-event-exchange","order.create.order",order.getOrder());
                    return respVo;
                }else{
                    //????????????
                    String msg = (String) r.get("msg");
                    throw new NoStockException(msg);
                }
            }else {
                respVo.setCode(2);
                return respVo;
            }
        }

//        String redisToken = redisTemplate.opsForValue().get(OrderConstant.USER_ORDER_TOKEN_PREFIX + memberRespVo.getId());
//        if (orderToken!=null && orderToken.equals(redisToken)){
//            //??????
//            redisTemplate.delete(OrderConstant.USER_ORDER_TOKEN_PREFIX + memberRespVo.getId());
//        }else {
//
//        }
    }

    /**
     * ??????????????????????????????
     * @param orderSn
     * @return
     */
    @Override
    public OrderEntity getOrderByOrderSn(String orderSn) {
        OrderEntity order_sn = this.getOne(new QueryWrapper<OrderEntity>().eq("order_sn", orderSn));
        return order_sn;
    }

    /**
     * ????????????
     * @param orderEntity
     */
    @Override
    public void closeOrder(OrderEntity orderEntity) {
        //???????????????????????????
        OrderEntity byId = this.getById(orderEntity.getId());
        if (byId.getStatus()==OrderStatusEnum.CREATE_NEW.getCode()){
            //?????????????????????????????????
            //
            OrderEntity update = new OrderEntity();
            update.setId(orderEntity.getId());
            update.setStatus(OrderStatusEnum.CANCLED.getCode());
            this.updateById(update);
            //???????????????????????????MQ????????????
            OrderCloseTo orderCloseTo = new OrderCloseTo();
            BeanUtils.copyProperties(byId,orderCloseTo);
            try{
                //?????????????????????????????????????????????????????????
                //?????????????????????????????????????????????????????????
                rabbitTemplate.convertAndSend("order-event-exchange","order.release.other",orderCloseTo);

            }catch (Exception e){

            }
        }
    }

    /**
     * ?????????????????????????????????
     * @param orderSn
     * @return
     */
    @Override
    public PayVo getOrderPay(String orderSn) {
        PayVo payVo = new PayVo();
        OrderEntity orderEntity = this.getOrderByOrderSn(orderSn);

        // .setScale(2, BigDecimal.ROUND_UP) ??????????????????????????????0.0001????????????????????????
        BigDecimal totalAmount = orderEntity.getTotalAmount().setScale(2, BigDecimal.ROUND_UP);
        payVo.setTotal_amount(totalAmount.toString());
        payVo.setOut_trade_no(orderEntity.getOrderSn());

        List<OrderItemEntity> items = orderItemService.list(new QueryWrapper<OrderItemEntity>().eq("order_sn", orderSn));
        OrderItemEntity orderItemEntity = items.get(0);
        String spuName = orderItemEntity.getSpuName();
        payVo.setSubject(spuName);
        payVo.setBody(spuName);
        return payVo;
    }

    @Override
    public PageUtils queryPageWithItem(Map<String, Object> params) {
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>().eq("member_id",memberRespVo.getId()).orderByDesc("id")
        );
        List<OrderEntity> collect = page.getRecords().stream().map(order -> {
            List<OrderItemEntity> orderItemEntities = orderItemService.list(new QueryWrapper<OrderItemEntity>().eq("order_sn", order.getOrderSn()));
            order.setItemEntities(orderItemEntities);
            return order;
        }).collect(Collectors.toList());

        page.setRecords(collect);
        return new PageUtils(page);
    }

    /**
     * ???????????????????????????
     * @param vo
     * @return
     */
    @Override
    public String handleAlipayed(PayAsyncVo vo) {
        //?????????????????????
        PaymentInfoEntity paymentInfoEntity = new PaymentInfoEntity();
        paymentInfoEntity.setAlipayTradeNo(vo.getTrade_no());
        paymentInfoEntity.setOrderSn(vo.getOut_trade_no());
        paymentInfoEntity.setPaymentStatus(vo.getTrade_status());
        paymentInfoEntity.setCallbackTime(vo.getNotify_time());
        paymentInfoService.save(paymentInfoEntity);
        //?????????????????????
        if (vo.getTrade_status().equals("TRADE_SUCCESS")||vo.getTrade_status().equals("TRADE_FINISHED")){
            String outTradeNo = vo.getOut_trade_no();
            //this.updateOrderStatus(outTradeNo,OrderStatusEnum.PAYED.getCode());
            this.baseMapper.updateOrderStatus(outTradeNo,OrderStatusEnum.PAYED.getCode());
        }
        return "success";
    }

//    private void updateOrderStatus(String outTradeNo, Integer code) {
//    }

    //????????????
    private void saveOrder(OrderCreateTo order) {
        OrderEntity orderEntity = order.getOrder();
        orderEntity.setModifyTime(new Date());
//        this.baseMapper.insert(orderEntity);
        //???????????????service  OrderService
        this.save(orderEntity);
        List<OrderItemEntity> orderItems = order.getOrderItems();
//        orderItemDao
        orderItemService.saveBatch(orderItems);

    }


    private OrderCreateTo createOrder(){
        OrderCreateTo orderCreateTo = new OrderCreateTo();

        //???????????????
        String orderSn = IdWorker.getTimeId();
        //1.????????????
        OrderEntity orderEntity = buildOrder(orderSn);

        //2.????????????????????????
        List<OrderItemEntity> itemEntities = buildOrderItems(orderSn);
        //3.??????
        computePrice(orderEntity,itemEntities);
        orderCreateTo.setOrder(orderEntity);
        orderCreateTo.setOrderItems(itemEntities);
        return orderCreateTo;
    }

    /**????????????*/
    private void computePrice(OrderEntity orderEntity, List<OrderItemEntity> orderItemEntities) {
        BigDecimal total = new BigDecimal("0.0");

        BigDecimal coupon = new BigDecimal("0.0");
        BigDecimal integration = new BigDecimal("0.0");
        BigDecimal promotion = new BigDecimal("0.0");
        BigDecimal giftIntegration = new BigDecimal("0.0");
        BigDecimal giftGrowth = new BigDecimal("0.0");

        for (OrderItemEntity orderItemEntity : orderItemEntities) {
            BigDecimal realAmount = orderItemEntity.getRealAmount();
            coupon = coupon.add(orderItemEntity.getCouponAmount());
            integration = integration.add(orderItemEntity.getIntegrationAmount());
            promotion = promotion.add(orderItemEntity.getPromotionAmount());
            total = total.add(realAmount);
            giftIntegration.add(new BigDecimal(orderItemEntity.getGiftIntegration().toString()));
            giftGrowth.add(new BigDecimal(orderItemEntity.getGiftGrowth().toString()));

        }
        //??????????????????
        orderEntity.setTotalAmount(total);
        orderEntity.setPayAmount(total.add(orderEntity.getFreightAmount()));

        orderEntity.setPromotionAmount(promotion);
        orderEntity.setIntegrationAmount(integration);
        orderEntity.setCouponAmount(coupon);


        //??????
        orderEntity.setIntegration(giftIntegration.intValue());
        orderEntity.setGrowth(giftGrowth.intValue());
        //????????????
        orderEntity.setDeleteStatus(0);//0 ?????????

    }

    /**
     * ????????????
     * @param orderSn
     * @return
     */
    private OrderEntity buildOrder(String orderSn) {
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
        OrderEntity entity = new OrderEntity();
        entity.setOrderSn(orderSn);
        entity.setMemberId(memberRespVo.getId());
        //?????????????????????????????????????????????vo??????ThreadLocal????????????
        OrderSubmitVo orderSubmitVo = OrderSubmitVoThreadLocal.get();
        R fare = stockFeignService.getFare(orderSubmitVo.getAddrId());
        FareVo data = fare.getData(new TypeReference<FareVo>() {});
        //????????????
        BigDecimal fare1 = data.getFare();
        entity.setFreightAmount(fare1);
        //?????????????????????
        entity.setReceiverProvince(data.getAddress().getProvince());
        entity.setReceiverCity(data.getAddress().getCity());
        entity.setReceiverDetailAddress(data.getAddress().getDetailAddress());
        entity.setReceiverName(data.getAddress().getName());
        entity.setReceiverPhone(data.getAddress().getPhone());
        entity.setReceiverPostCode(data.getAddress().getPostCode());
        entity.setReceiverRegion(data.getAddress().getRegion());

        //??????????????????
        entity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());
        //??????????????????
        entity.setAutoConfirmDay(7);
        return entity;
    }

    /**
     * ???????????????
     * @return
     */
    private List<OrderItemEntity> buildOrderItems(String orderSn) {
        //?????????????????????????????????
        List<OrderItemVo> currentUserCartItems = cartFeignService.getCurrentUserCartItems();
        if (currentUserCartItems!=null && currentUserCartItems.size()>0){
            List<OrderItemEntity> collect = currentUserCartItems.stream().map(cartItem -> {
                OrderItemEntity itemEntity = buildOrderItem(cartItem);
                itemEntity.setOrderSn(orderSn);
                return itemEntity;
            }).collect(Collectors.toList());
            return collect;
        }
        return null;
    }

    /**
     *????????????????????????
     * @param cartItem
     * @return
     */
    private OrderItemEntity buildOrderItem(OrderItemVo cartItem) {

        OrderItemEntity orderItemEntity = new OrderItemEntity();

        Long skuId = cartItem.getSkuId();
        R r = goodsFeignService.getSpuInfoBySkuId(skuId);
        SpuInfoVo data = r.getData(new TypeReference<SpuInfoVo>() {});

        orderItemEntity.setSpuId(data.getId());
        orderItemEntity.setSpuBrand(data.getBrandId().toString());
        orderItemEntity.setSpuName(data.getSpuName());
        orderItemEntity.setCategoryId(data.getCatalogId());


        orderItemEntity.setSkuId(cartItem.getSkuId());
        orderItemEntity.setSkuName(cartItem.getTitle());
        orderItemEntity.setSkuPrice(cartItem.getPrice());
        orderItemEntity.setSkuPic(cartItem.getImage());
        //spring??????,???????????????????????????????????????String
        String skuAttr = StringUtils.collectionToDelimitedString(cartItem.getSkuAttr(), ";");
        orderItemEntity.setSkuAttrsVals(skuAttr);
        orderItemEntity.setSkuQuantity(cartItem.getCount());

        //intValue()
        orderItemEntity.setGiftGrowth(cartItem.getPrice().multiply(new BigDecimal(cartItem.getCount().toString())).intValue());
        orderItemEntity.setGiftIntegration(cartItem.getPrice().multiply(new BigDecimal(cartItem.getCount().toString())).intValue());

        orderItemEntity.setPromotionAmount(BigDecimal.ZERO);
        orderItemEntity.setCouponAmount(BigDecimal.ZERO);
        orderItemEntity.setIntegrationAmount(new BigDecimal("0"));
        BigDecimal orgin = orderItemEntity.getSkuPrice().multiply(new BigDecimal(orderItemEntity.getSkuQuantity().toString()));
        BigDecimal real = orgin.subtract(orderItemEntity.getCouponAmount())
                .subtract(orderItemEntity.getPromotionAmount())
                .subtract(orderItemEntity.getIntegrationAmount());
        orderItemEntity.setRealAmount(real);
        return orderItemEntity;
    }
}
