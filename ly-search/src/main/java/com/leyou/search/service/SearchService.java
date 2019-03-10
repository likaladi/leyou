package com.leyou.search.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leyou.common.pojo.PageResult;
import com.leyou.common.pojo.SearchRequest;
import com.leyou.item.pojo.*;
import com.leyou.item.vo.SpecParam;
import com.leyou.search.client.BrandClient;
import com.leyou.search.client.CategoryClient;
import com.leyou.search.client.GoodsClient;
import com.leyou.search.client.SpecificationClient;
import com.leyou.search.pojo.Goods;
import com.leyou.search.pojo.SearchResult;
import com.leyou.search.repository.GoodsRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.LongTerms;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.aggregation.AggregatedPage;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Slf4j
@Service
public class SearchService {

    @Autowired
    private ElasticsearchTemplate elasticsearchTemplate;

    @Autowired
    private CategoryClient categoryClient;

    @Autowired
    private GoodsClient goodsClient;

    @Autowired
    private SpecificationClient specificationClient;

    @Autowired
    private BrandClient brandClient;

    @Autowired
    private GoodsRepository goodsRepository;

    private ObjectMapper mapper = new ObjectMapper();

    public Goods buildGoods(Spu spu) throws Exception {
        Goods goods = new Goods();

        // 查询商品分类名称
        List<String> names = this.categoryClient.queryNameByIds(Arrays.asList(spu.getCid1(), spu.getCid2(), spu.getCid3()));

        // 查询sku
        List<Sku> skus = this.goodsClient.querySkuBySpuId(spu.getId());
        // 查询详情
        SpuDetail spuDetail = this.goodsClient.querySpuDetailById(spu.getId());
        // 查询规格参数
        List<SpecParam> params = this.specificationClient.querySpecParam(null, spu.getCid3(), true, null);

        // 处理sku，仅封装id、价格、标题、图片，并获得价格集合
        List<Long> prices = new ArrayList<>();
        List<Map<String, Object>> skuList = new ArrayList<>();
        skus.forEach(sku -> {
            prices.add(sku.getPrice());
            Map<String, Object> skuMap = new HashMap<>();
            skuMap.put("id", sku.getId());
            skuMap.put("title", sku.getTitle());
            skuMap.put("price", sku.getPrice());
            skuMap.put("image", StringUtils.isBlank(sku.getImages()) ? "" : StringUtils.split(sku.getImages(), ",")[0]);
            skuList.add(skuMap);
        });

        //将商品详情规格属性转为key value形式
        Map<String, Object> specTempMap = getSpecMap(spuDetail.getSpecifications(), spuDetail.getSpecTemplate());


        // 获取可搜索的规格参数
        Map<String, Object> searchSpec = new HashMap<>();

        // 过滤规格模板，把所有可搜索的信息保存到Map中
        Map<String, Object> specMap = new HashMap<>();
        params.forEach(p -> {
            if (p.getSearching()) {
                if (p.getGeneric()) {
                    String value = (String) specTempMap.get(p.getFiledName());
                    if(p.getNumeric() != null && p.getNumeric()){
                        value = chooseSegment(value, p);
                    }
                    specMap.put(p.getFiledName(), StringUtils.isBlank(value) ? "其它" : value);
                } else {
                    specMap.put(p.getFiledName(), specTempMap.get(p.getFiledName()));
                }
            }
        });

        goods.setId(spu.getId());
        goods.setSubTitle(spu.getSubTitle());
        goods.setBrandId(spu.getBrandId());
        goods.setCid1(spu.getCid1());
        goods.setCid2(spu.getCid2());
        goods.setCid3(spu.getCid3());
        goods.setCreateTime(spu.getCreateTime());
        //goods.setAll(spu.getTitle() + " " + StringUtils.join(names, " "));
        goods.setAll(spu.getTitle());
        goods.setPrice(prices);
        goods.setSkus(mapper.writeValueAsString(skuList));
        goods.setSpecs(specMap);
        return goods;
    }

    private Map<String, Object> getSpecMap(String specifications, String specTemplate) throws Exception{
        Map<String, Object> specsMap = mapper.readValue(specTemplate, new TypeReference<Map<String, Object>>() {});
        new JsonParser().parse(specifications).getAsJsonArray().forEach(arr -> {
            arr.getAsJsonObject().get("params").getAsJsonArray().forEach(arr2 -> {
                JsonObject jsonObject = arr2.getAsJsonObject();
                boolean global = jsonObject.get("global").getAsBoolean();
                if(global){
                    String val = jsonObject.get("v").toString();
                    val = val.equals("null")?null:jsonObject.get("v").getAsString();
                    specsMap.put(jsonObject.get("k").getAsString(), val);
                }
            });
        });
        return specsMap;
    }

    public void createIndex(Long id) throws Exception {

        Spu spu = this.goodsClient.querySpuById(id);
        // 构建商品
        Goods goods = this.buildGoods(spu);

        // 保存数据到索引库
        this.goodsRepository.save(goods);
    }

    public void deleteIndex(Long id) {
        this.goodsRepository.deleteById(id);
    }


    public static void main(String[] args) {
        String tstr = "5.0";
        System.out.println(NumberUtils.toDouble(tstr));
    }

    private String chooseSegment(String value, SpecParam p) {
        double val = NumberUtils.toDouble(value);
        String result = "其它";
        String options = p.getOptions();
        JsonArray jsonArray =  new JsonParser().parse(options).getAsJsonArray();
        if(jsonArray.size() > 0){
            for(JsonElement arr : jsonArray){
                String segment = arr.getAsString();
                String[] segs = segment.split("-");
                // 获取数值范围
                double begin = NumberUtils.toDouble(segs[0]);
                double end = Double.MAX_VALUE;
                if(segs.length == 2){
                    end = NumberUtils.toDouble(segs[1]);
                }
                // 判断是否在范围内
                if(val >= begin && val <= end){
                    if(segs.length == 1){
                        result = segs[0] + p.getUnit() + "以上";
                    }else if(begin == 0){
                        result = segs[1] + p.getUnit() + "以下";
                    }else{
                        result = segment + p.getUnit();
                    }
                    break;
                }
            }
        }
        // 保存数值段
//        for (String segment : p.getSegments().split(",")) {
//            String[] segs = segment.split("-");
//            // 获取数值范围
//            double begin = NumberUtils.toDouble(segs[0]);
//            double end = Double.MAX_VALUE;
//            if(segs.length == 2){
//                end = NumberUtils.toDouble(segs[1]);
//            }
//            // 判断是否在范围内
//            if(val >= begin && val < end){
//                if(segs.length == 1){
//                    result = segs[0] + p.getUnit() + "以上";
//                }else if(begin == 0){
//                    result = segs[1] + p.getUnit() + "以下";
//                }else{
//                    result = segment + p.getUnit();
//                }
//                break;
//            }
//        }
        return result;
    }

//    public PageResult<Goods> search(SearchRequest request) {
//        String key = request.getKey();
//        // 判断是否有搜索条件，如果没有，直接返回null。不允许搜索全部商品
//        if (StringUtils.isBlank(key)) {
//            return null;
//        }
//
//        // 构建查询条件
//        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
//
//        // 1、对key进行全文检索查询
//        queryBuilder.withQuery(QueryBuilders.matchQuery("all", key).operator(Operator.AND));
//
//        // 2、通过sourceFilter设置返回的结果字段,我们只需要id、skus、subTitle
//        queryBuilder.withSourceFilter(new FetchSourceFilter(
//                new String[]{"id","skus","subTitle"}, null));
//
//        // 3、分页
//        // 准备分页参数
//        int page = request.getPage();
//        int size = request.getSize();
//        queryBuilder.withPageable(PageRequest.of(page - 1, size));
//
//        //排序
//        String sortBy = request.getSortBy();
//        Boolean desc = request.getDescending();
//        if(StringUtils.isNotBlank(sortBy)){
//            //如果不为空，则进行排序
//            queryBuilder.withSort(SortBuilders.fieldSort(sortBy).order(desc ? SortOrder.DESC : SortOrder.ASC));
//        }
//
//        // 4、查询，获取结果
//        Page<Goods> pageInfo = this.goodsRepository.search(queryBuilder.build());
//
//
//        // 封装结果并返回
//        return new PageResult<>(pageInfo.getTotalElements(), Long.parseLong(pageInfo.getTotalPages()+""), pageInfo.getContent());
//    }

    public PageResult<Goods> search(SearchRequest request) {
        // 判断是否有搜索条件，如果没有，直接返回null。不允许搜索全部商品
        if (StringUtils.isBlank(request.getKey())) {
            return null;
        }

        // 1、构建查询条件
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();

        //MatchQueryBuilder basicQuery = QueryBuilders.matchQuery("all",  request.getKey()).operator(Operator.AND);
        QueryBuilder basicQuery = buildBasicQueryWithFilter(request);

        // 1.1、基本查询
        queryBuilder.withQuery(basicQuery);
        // 通过sourceFilter设置返回的结果字段,我们只需要id、skus、subTitle
        queryBuilder.withSourceFilter(new FetchSourceFilter(
                new String[]{"id", "skus", "subTitle"}, null));

        // 1.2.分页排序
        searchWithPageAndSort(queryBuilder,request);

        // 1.3、聚合
        String categoryAggName = "category"; // 商品分类聚合名称
        String brandAggName = "brand"; // 品牌聚合名称
        // 对商品分类进行聚合
        queryBuilder.addAggregation(AggregationBuilders.terms(categoryAggName).field("cid3"));
        // 对品牌进行聚合
        queryBuilder.addAggregation(AggregationBuilders.terms(brandAggName).field("brandId"));

        // 2、查询，获取结果
        AggregatedPage<Goods> pageInfo = (AggregatedPage<Goods>) this.goodsRepository.search(queryBuilder.build());

        // 3.2、商品分类的聚合结果
        List<Category> categories =
                getCategoryAggResult(pageInfo.getAggregation(categoryAggName));
        // 3.3、品牌的聚合结果
        List<Brand> brands = getBrandAggResult(pageInfo.getAggregation(brandAggName));

        // 根据商品分类判断是否需要聚合
        List<Map<String, Object>> specs = new ArrayList<>();
        if (categories.size() == 1) {
            // 如果商品分类只有一个才进行聚合，并根据分类与基本查询条件聚合
            specs = getSpec(categories.get(0).getId(), basicQuery);
        }

        // 返回结果
        return new SearchResult(pageInfo.getTotalElements(), pageInfo.getTotalPages(), pageInfo.getContent(), categories, brands, specs);
    }

    // 构建基本查询条件
    private QueryBuilder buildBasicQueryWithFilter(SearchRequest request) {
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        // 基本查询条件
        queryBuilder.must(QueryBuilders.matchQuery("all", request.getKey()).operator(Operator.AND));

        // 整理过滤条件
        Map<String, String> filter = request.getFilter();
        if(filter != null){
            // 过滤条件构建器
            BoolQueryBuilder filterQueryBuilder = QueryBuilders.boolQuery();

            for (Map.Entry<String, String> entry : filter.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                // 商品分类和品牌要特殊处理
                if (key != "cid3" && key != "brandId") {
                    key = "specs." + key + ".keyword";
                }
                // 字符串类型，进行term查询
                filterQueryBuilder.must(QueryBuilders.termQuery(key, value));
            }
            // 添加过滤条件
            queryBuilder.filter(filterQueryBuilder);
        }
        return queryBuilder;
    }


    /**
     * 聚合出规格参数
     *
     * @param cid
     * @param query
     * @return
     */
    private List<Map<String, Object>> getSpec(Long cid, QueryBuilder query) {
        try {
            // 不管是全局参数还是sku参数，只要是搜索参数，都根据分类id查询出来
            List<SpecParam> params = this.specificationClient.querySpecParam(null, cid, true, null);
            List<Map<String, Object>> specs = new ArrayList<>();

            NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
            queryBuilder.withQuery(query);

            // 聚合规格参数
            params.forEach(p -> {
                String key = p.getFiledName();
                //因为规格参数保存时不做分词，因此其名称会自动带上一个.keyword后缀
                queryBuilder.addAggregation(AggregationBuilders.terms(key).field("specs." + key + ".keyword"));

            });

            // 查询
            Map<String, Aggregation> aggs = this.elasticsearchTemplate.query(queryBuilder.build(),
                    SearchResponse::getAggregations).asMap();

            // 解析聚合结果
            params.forEach(param -> {
                Map<String, Object> spec = new HashMap<>();
                String key = param.getFiledName();
                spec.put("k", key);
                StringTerms terms = (StringTerms) aggs.get(key);
                spec.put("options", terms.getBuckets().stream().map(StringTerms.Bucket::getKeyAsString));
                specs.add(spec);
            });

            return specs;
        } catch (Exception e){
            log.error("规格聚合出现异常：", e);
            return null;
        }

    }


    // 解析品牌聚合结果
    private List<Brand> getBrandAggResult(Aggregation aggregation) {
        try {
            LongTerms brandAgg = (LongTerms) aggregation;
            List<Long> bids = new ArrayList<>();
            for (LongTerms.Bucket bucket : brandAgg.getBuckets()) {
                bids.add(bucket.getKeyAsNumber().longValue());
            }
            // 根据id查询品牌
            return this.brandClient.queryBrandByIds(bids);
        } catch (Exception e){
            log.error("品牌聚合出现异常：", e);
            return null;
        }
    }

    // 解析商品分类聚合结果
    private List<Category> getCategoryAggResult(Aggregation aggregation) {
        try{
            List<Category> categories = new ArrayList<>();
            LongTerms categoryAgg = (LongTerms) aggregation;
            List<Long> cids = new ArrayList<>();
            for (LongTerms.Bucket bucket : categoryAgg.getBuckets()) {
                cids.add(bucket.getKeyAsNumber().longValue());
            }
            // 根据id查询分类名称
            List<String> names = this.categoryClient.queryNameByIds(cids);

            for (int i = 0; i < names.size(); i++) {
                Category c = new Category();
                c.setId(cids.get(i));
                c.setName(names.get(i));
                categories.add(c);
            }
            return categories;
        } catch (Exception e){
            log.error("分类聚合出现异常：", e);
            return null;
        }
    }

    // 构建基本查询条件
    private void searchWithPageAndSort(NativeSearchQueryBuilder queryBuilder, SearchRequest request) {
        // 准备分页参数
        int page = request.getPage();
        int size = request.getSize();

        // 1、分页
        queryBuilder.withPageable(PageRequest.of(page - 1, size));
        // 2、排序
        String sortBy = request.getSortBy();
        Boolean desc = request.getDescending();
        if (StringUtils.isNotBlank(sortBy)) {
            // 如果不为空，则进行排序
            queryBuilder.withSort(SortBuilders.fieldSort(sortBy).order(desc ? SortOrder.DESC : SortOrder.ASC));
        }
    }
}
