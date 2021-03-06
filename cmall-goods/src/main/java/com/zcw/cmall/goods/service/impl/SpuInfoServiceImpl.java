package com.zcw.cmall.goods.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.zcw.cmall.goods.entity.*;
import com.zcw.cmall.goods.feign.CouponFeignService;
import com.zcw.cmall.goods.feign.SearchFeignService;
import com.zcw.cmall.goods.feign.StockFeignService;
import com.zcw.cmall.goods.service.*;
import com.zcw.cmall.goods.vo.*;
import com.zcw.common.constant.GoodsConstant;
import com.zcw.common.to.SkuHasStockVo;
import com.zcw.common.to.SkuReductionTo;
import com.zcw.common.to.SpuBoundTo;
import com.zcw.common.to.es.SkuEsModel;
import com.zcw.common.utils.R;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcw.common.utils.PageUtils;
import com.zcw.common.utils.Query;

import com.zcw.cmall.goods.dao.SpuInfoDao;
import org.springframework.transaction.annotation.Transactional;


@Service("spuInfoService")
public class SpuInfoServiceImpl extends ServiceImpl<SpuInfoDao, SpuInfoEntity> implements SpuInfoService {

    @Autowired
    SearchFeignService searchFeignService;
    @Autowired
    StockFeignService stockFeignService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    BrandService brandService;
    @Autowired
    SpuInfoDescService descService;
    @Autowired
    SpuImagesService imagesService;
    @Autowired
    AttrService attrService;
    @Autowired
    ProductAttrValueService attrValueService;
    @Autowired
    SkuInfoService skuInfoService;
    @Autowired
    SkuImagesService skuImagesService;
    @Autowired
    SkuSaleAttrValueService skuSaleAttrValueService;
    @Autowired
    CouponFeignService couponFeignService;
    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                new QueryWrapper<SpuInfoEntity>()
        );

        return new PageUtils(page);
    }

    @Transactional
    @Override
    public void saveSpuInfo(SpuSaveVo vo) {
        //1?????????spu???????????? pms_spu_info
        SpuInfoEntity spuInfoEntity = new SpuInfoEntity();
        BeanUtils.copyProperties(vo,spuInfoEntity);
        spuInfoEntity.setCreateTime(new Date());
        spuInfoEntity.setUpdateTime(new Date());
        this.saveBaseSpuInfo(spuInfoEntity);

        //2?????????Spu??????????????? pms_spu_info_desc
        List<String> decript = vo.getDecript();
        SpuInfoDescEntity spuInfoDescEntity = new SpuInfoDescEntity();
        spuInfoDescEntity.setSpuId(spuInfoEntity.getId());
        //?????????????????????String.join(",",stringxxx)?????????
        spuInfoDescEntity.setDecript(String.join(",",decript));
        descService.saveSpuInfoDesc(spuInfoDescEntity);
        //3?????????spu???????????? pms_spu_images
        List<String> images = vo.getImages();
        //?????????spuId???images??????
        imagesService.saveImages(spuInfoEntity.getId(),images);


        //4?????????spu???????????????;pms_product_attr_value
        List<BaseAttrs> baseAttrs = vo.getBaseAttrs();
        List<ProductAttrValueEntity> attrValues = baseAttrs.stream().map(item -> {
            ProductAttrValueEntity attrValueEntity = new ProductAttrValueEntity();
            attrValueEntity.setAttrId(item.getAttrId());
            //????????????id????????????entity
            AttrEntity attrEntity = attrService.getById(item.getAttrId());
            attrValueEntity.setAttrName(attrEntity.getAttrName());
            attrValueEntity.setAttrValue(item.getAttrValues());
            attrValueEntity.setQuickShow(item.getShowDesc());
            attrValueEntity.setSpuId(spuInfoEntity.getId());
            return attrValueEntity;
        }).collect(Collectors.toList());
        //??????spu
        attrValueService.saveProductAttr(attrValues);

        //5?????????spu??????????????????gulimall_sms->sms_spu_bounds
        Bounds bounds = vo.getBounds();
        SpuBoundTo spuBoundTo = new SpuBoundTo();
        //????????????spuid
        BeanUtils.copyProperties(bounds,spuBoundTo);
        spuBoundTo.setSpuId(spuInfoEntity.getId());
        //Feign?????????????????????
        R r = couponFeignService.saveSpuBounds(spuBoundTo);
        if(r.getCode() != 0){
            log.error("????????????spu??????????????????");
        }

        //5???????????????spu???????????????sku?????????

        List<Skus> skus = vo.getSkus();
        if(skus!=null && skus.size()>0){
            skus.forEach(item->{
                String defaultImg = "";
                for (Images image : item.getImages()) {
                    if(image.getDefaultImg() == 1){
                        defaultImg = image.getImgUrl();
                    }
                }
                //    private String skuName;
                //    private BigDecimal price;
                //    private String skuTitle;
                //    private String skuSubtitle;
                SkuInfoEntity skuInfoEntity = new SkuInfoEntity();
                BeanUtils.copyProperties(item,skuInfoEntity);
                skuInfoEntity.setBrandId(spuInfoEntity.getBrandId());
                skuInfoEntity.setCatalogId(spuInfoEntity.getCatalogId());
                skuInfoEntity.setSaleCount(0L);
                skuInfoEntity.setSpuId(spuInfoEntity.getId());
                skuInfoEntity.setSkuDefaultImg(defaultImg);
                //5.1??????sku??????????????????pms_sku_info
                skuInfoService.saveSkuInfo(skuInfoEntity);

                Long skuId = skuInfoEntity.getSkuId();

                List<SkuImagesEntity> imagesEntities = item.getImages().stream().map(img -> {
                    SkuImagesEntity skuImagesEntity = new SkuImagesEntity();
                    skuImagesEntity.setSkuId(skuId);
                    skuImagesEntity.setImgUrl(img.getImgUrl());
                    skuImagesEntity.setDefaultImg(img.getDefaultImg());
                    return skuImagesEntity;
                }).filter(entity->{
                    //??????true???????????????false????????????
                    return !StringUtils.isEmpty(entity.getImgUrl());
                }).collect(Collectors.toList());
                //5.2??????sku??????????????????pms_sku_image
                skuImagesService.saveBatch(imagesEntities);
                //TODO ?????????????????????????????????

                List<Attr> attr = item.getAttr();
                List<SkuSaleAttrValueEntity> skuSaleAttrValueEntities = attr.stream().map(a -> {
                    SkuSaleAttrValueEntity attrValueEntity = new SkuSaleAttrValueEntity();
                    BeanUtils.copyProperties(a, attrValueEntity);
                    attrValueEntity.setSkuId(skuId);

                    return attrValueEntity;
                }).collect(Collectors.toList());
                //5.3??????sku????????????????????????pms_sku_sale_attr_value
                skuSaleAttrValueService.saveBatch(skuSaleAttrValueEntities);

                // //5.4??????sku??????????????????????????????gulimall_sms->sms_sku_ladder\sms_sku_full_reduction\sms_member_price
                SkuReductionTo skuReductionTo = new SkuReductionTo();
                BeanUtils.copyProperties(item,skuReductionTo);
                skuReductionTo.setSkuId(skuId);
                if(skuReductionTo.getFullCount() >0 || skuReductionTo.getFullPrice().compareTo(new BigDecimal("0")) == 1){
                    R r1 = couponFeignService.saveSkuReduction(skuReductionTo);
                    if(r1.getCode() != 0){
                        log.error("????????????sku??????????????????");
                    }
                }



            });
        }
    }

    //1?????????spu???????????? pms_spu_info
    @Override
    public void saveBaseSpuInfo(SpuInfoEntity spuInfoEntity) {
        this.baseMapper.insert(spuInfoEntity);
    }

    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {
        QueryWrapper<SpuInfoEntity> wrapper = new QueryWrapper<>();
        String key = (String) params.get("key");
        if(!StringUtils.isEmpty(key)){
            //?????????????????????????????????????????????????????????????????????????????????????????????????????????
            wrapper.and(w -> {
                w.eq("id",key).or().like("spu_name",key);
            });
        }
        String status = (String) params.get("status");
        if(!StringUtils.isEmpty(status)){
            wrapper.eq("publish_status",status);
        }
        String brandId = (String) params.get("brandId");
        if(!StringUtils.isEmpty(brandId) && !"0".equalsIgnoreCase(brandId)){
            wrapper.eq("brand_id",brandId);
        }
        String catelogId = (String) params.get("catalogId");
        if(!StringUtils.isEmpty(catelogId) && !"0".equalsIgnoreCase(catelogId)){
            wrapper.eq("catalog_id",catelogId);
        }

        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    /**
     * ????????????
     * @param spuId
     */
    @Override
    public void up(Long spuId) {

        //1.????????????
        //SkuEsModel skuEsModel = new SkuEsModel();
        //??????spuId????????????sku???????????????????????????????
        List<SkuInfoEntity> skuInfoEntities = skuInfoService.getSkusBySpuId(spuId);
        List<Long> skuIdList = skuInfoEntities.stream().map(SkuInfoEntity::getSkuId).collect(Collectors.toList());

        //TODO ????????????sku????????????????????????????????????
        List<ProductAttrValueEntity> base = attrValueService.baseAttrListForSpu(spuId);
        //??????stream?????????????????????????????????????????????debug???????????????????????????????????????????????????????????????
        List<Long> ids = base.stream().map(attr -> {
            return attr.getAttrId();
        }).collect(Collectors.toList());


        List<Long> searchAttrIds = attrService.selectSearchAttrs(ids);
        Set<Long> idSet = new HashSet<>(searchAttrIds);


        List<SkuEsModel.Attr> collect = base.stream().filter(attr -> {
            return idSet.contains(attr.getAttrId());
        }).map(attr -> {

            SkuEsModel.Attr attr1 = new SkuEsModel.Attr();
            BeanUtils.copyProperties(attr, attr1);
            return attr1;
        }).collect(Collectors.toList());
        //TODO hasStock(?????????????????????????????????????????????),hotScore(?????? 0)

        Map<Long, Boolean> map = null;
        try{
            R r = stockFeignService.getSkusHasStock(skuIdList);
            //???????????????R??????data????????????key???data?????????list????????????list
            //new TypeReference<List<SkuHasStockVo>>(){}  ?????????  new?????????TypeReference????????????????????????????????? protected TypeReference() {} ??????????????????
            //debug??????????????????
            map = r.getData(new TypeReference<List<SkuHasStockVo>>(){}).stream().collect(Collectors.toMap(SkuHasStockVo::getSkuId, item -> item.getHasStock()));

        }catch (Exception e){
            log.error("??????????????????:{}",e);
        }
        //????????????sku??????
        Map<Long, Boolean> finalMap = map;
        List<SkuEsModel> upGoods = skuInfoEntities.stream().map(sku -> {
            SkuEsModel esModel = new SkuEsModel();
            BeanUtils.copyProperties(sku,esModel);
            esModel.setSkuPrice(sku.getPrice());
            esModel.setSkuImg(sku.getSkuDefaultImg());

            if(finalMap ==null){
                esModel.setHasStock(true);
            }
            else{
                esModel.setHasStock(finalMap.get(sku.getSkuId()));
            }

            esModel.setHotScore(0L);

            BrandEntity brand = brandService.getById(esModel.getBrandId());
            esModel.setBrandName(brand.getName());
            esModel.setBrandImg(brand.getLogo());

            CategoryEntity category = categoryService.getById(esModel.getCatalogId());
            esModel.setCatalogName(category.getName());

            //??????????????????
            esModel.setAttrs(collect);
            //TODO
            return esModel;
        }).collect(Collectors.toList());
        //TODO ??????????????????es??????  cmall-search

        R r = searchFeignService.goodStatusUp(upGoods);
        if (r.getCode()==0){
            this.baseMapper.upSpuStatus(spuId, GoodsConstant.StatusEnum.UP_SPU.getCode());
        }else {
            log.error("????????????es????????????");
        }
    }

    /**
     * ??????skuId???spu??????
     * @param skuIdg
     * @return
     */
    @Override
    public SpuInfoEntity getSpuInfoBySkuId(Long skuId) {
        SkuInfoEntity skuInfoEntity = skuInfoService.getById(skuId);
        Long spuId = skuInfoEntity.getSpuId();
        SpuInfoEntity spu = getById(spuId);
        return spu;
    }


}
