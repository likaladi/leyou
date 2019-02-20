package com.leyou.item.pojo;

import lombok.Data;

import javax.persistence.Id;
import javax.persistence.Table;

@Data
@Table(name="tb_spu_detail")
public class SpuDetail {
    @Id
    private Long spuId;// 对应的SPU的id
    private String description;// 商品描述
    private String specTemplate;// 商品特殊规格的名称及可选值模板
    private String packingList;// 包装清单
    private String afterService;// 售后服务
    /**
     * sku规格属性模板
     */
    private String specialSpec;
    /**
     * 通用规格参数
     */
    private String genericSpec;
}
