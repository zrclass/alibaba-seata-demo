package org.zrclass.mall.ware.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zrclass.common.exception.NotStockException;
import org.zrclass.common.to.mq.StockDetailTo;
import org.zrclass.common.to.mq.StockLockedTo;
import org.zrclass.mall.ware.dao.WareSkuDao;
import org.zrclass.mall.ware.entity.WareOrderTaskDetailEntity;
import org.zrclass.mall.ware.entity.WareOrderTaskEntity;
import org.zrclass.mall.ware.entity.WareSkuEntity;
import org.zrclass.mall.ware.service.WareOrderTaskDetailService;
import org.zrclass.mall.ware.service.WareOrderTaskService;
import org.zrclass.mall.ware.service.WareSkuService;
import org.zrclass.mall.ware.vo.OrderItemVo;
import org.zrclass.mall.ware.vo.WareSkuLockVo;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;


@Service("wareSkuService")
//@RabbitListener(queues = "stock.release.stock.queue")
@Slf4j
public class WareSkuServiceImpl extends ServiceImpl<WareSkuDao, WareSkuEntity> implements WareSkuService {

    @Resource
    private WareSkuDao wareSkuDao;


    @Autowired
    private WareOrderTaskService orderTaskService;

    @Autowired
    private WareOrderTaskDetailService orderTaskDetailService;

    @Autowired
    private RabbitTemplate rabbitTemplate;


    @Value("${myRabbitmq.MQConfig.eventExchange}")
    private String eventExchange;

    @Value("${myRabbitmq.MQConfig.routingKey}")
    private String routingKey;




    @Transactional(rollbackFor = NotStockException.class)
    @Override
    public Boolean orderLockStock(WareSkuLockVo vo) {
        // 当定库存之前先保存订单 以便后来消息撤回
        WareOrderTaskEntity taskEntity = new WareOrderTaskEntity();
        taskEntity.setOrderSn(vo.getOrderSn());
        orderTaskService.save(taskEntity);
        // [理论上]1. 按照下单的收获地址 找到一个就近仓库, 锁定库存
        // [实际上]1. 找到每一个商品在那个一个仓库有库存
        List<OrderItemVo> locks = vo.getLocks();
        List<SkuWareHasStock> collect = locks.stream().map(item -> {
            SkuWareHasStock hasStock = new SkuWareHasStock();
            Long skuId = item.getSkuId();
            hasStock.setSkuId(skuId);
            // 查询这两个商品在哪有库存
            List<Long> wareIds = wareSkuDao.listWareIdHasSkuStock(skuId);
            hasStock.setWareId(wareIds);
            hasStock.setNum(item.getCount());
            return hasStock;
        }).collect(Collectors.toList());

        for (SkuWareHasStock hasStock : collect) {
            Boolean skuStocked = true;
            Long skuId = hasStock.getSkuId();
            List<Long> wareIds = hasStock.getWareId();
            if(wareIds == null || wareIds.size() == 0){
                // 没有任何仓库有这个库存
                throw new NotStockException(skuId.toString());
            }
            // 如果每一个商品都锁定成功 将当前商品锁定了几件的工作单记录发送给MQ
            // 如果锁定失败 前面保存的工作单信息回滚了
            for (Long wareId : wareIds) {
                // 成功就返回 1 失败返回0
                Long count = wareSkuDao.lockSkuStock(skuId, wareId, hasStock.getNum());
                if(count == 1){
                    // TODO 告诉MQ库存锁定成功 一个订单锁定成功 消息队列就会有一个消息
                    WareOrderTaskDetailEntity detailEntity = new WareOrderTaskDetailEntity(null,skuId,"",hasStock.getNum() ,taskEntity.getId(),wareId,1);
                    orderTaskDetailService.save(detailEntity);
                    StockLockedTo stockLockedTo = new StockLockedTo();
                    stockLockedTo.setId(taskEntity.getId());
                    StockDetailTo detailTo = new StockDetailTo();
                    BeanUtils.copyProperties(detailEntity, detailTo);
                    // 防止回滚以后找不到数据 把详细信息页
                    stockLockedTo.setDetailTo(detailTo);

                    //rabbitTemplate.convertAndSend(eventExchange, routingKey ,stockLockedTo);
                    skuStocked = false;
                    break;
                }
                // 当前仓库锁定失败 重试下一个仓库
            }
            if(skuStocked){
                // 当前商品在所有仓库都没锁柱
                throw new NotStockException(skuId.toString());
            }
        }
        // 3.全部锁定成功
        return true;
    }


    @Data
    class SkuWareHasStock{

        private Long skuId;

        private List<Long> wareId;

        private Integer num;
    }
}
