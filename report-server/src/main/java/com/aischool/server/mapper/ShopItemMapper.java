package com.aischool.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aischool.server.entity.ShopItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ShopItemMapper extends BaseMapper<ShopItem> {

    /** 原子扣库存（不限量 stock=-1 时不调用） */
    @Update("UPDATE t_shop_item SET stock = stock - 1 WHERE id = #{id} AND stock > 0")
    int deductStock(@Param("id") Long id);

    /** 批65：撤销兑换回库存（与 deductStock 对称；不限量不调用） */
    @Update("UPDATE t_shop_item SET stock = stock + 1 WHERE id = #{id}")
    int restoreStock(@Param("id") Long id);
}
