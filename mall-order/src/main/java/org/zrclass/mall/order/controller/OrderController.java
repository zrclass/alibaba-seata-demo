package org.zrclass.mall.order.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.zrclass.common.utils.R;
import org.zrclass.mall.order.service.OrderService;
import org.zrclass.mall.order.vo.OrderSubmitVo;
import org.zrclass.mall.order.vo.SubmitOrderResponseVo;

/**
 * @author zhourui 20114535
 * @version 1.0
 * @date 2021/6/8 10:35
 */
@RestController
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/submitOrder")
    public R submitOrder(@RequestBody OrderSubmitVo submitVo){
        SubmitOrderResponseVo responseVo = orderService.submitOrder(submitVo);
        return R.ok().setData(responseVo);
    }
}
