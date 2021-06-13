#### 一.分布式事务概述

分布式情况下，可能出现一些服务事务不一致的情况

* 远程服务假失败
* 远程服务执行完成后，下面其他方法出现异常

<img src="D:/a_mall/gulimall-learning-master/docs/images/Snipaste_2020-10-11_09-15-30.png" style="zoom:38%;" />

#### 二.分布式事务的解决方案

##### 1.2PC模式

数据库支持的2PC【二阶提交法】，又叫做XA Transactions。Mysql5.5开始支持，SQL Server 2005开始支持，Oracle7开始支持，XA是一个两阶段提交协议，该协议分为一下两阶段

第一阶段：事务协调器要求每个涉及到事务的数据库与提交此操作，并反应是否可以提交。

第二阶段：事务协调器要求每个数据库提交数据

如果有任何一个数据库否决此次提交，那么所有数据库都会被要求回滚它们在在此事务中提交的数据

特点：

- XA协议比较简单。而且一旦商业数据库实现了XA协议。使用分布式事务的成本也比较低
- XA性能不理想，特别是在交易下单链路。往往并发量很高，XA无法满足高并发场景
- XA目前在商业数据库支持的比较理想，在mysql数据库中支持的不太理想，mysql的XA实现没有记录prepare阶段日志，主备切换会导致主库与备库的数据不一致

##### 2.柔性事务-TCC事务补偿型方案

刚性事务，遵循ACID原则，强一致性

柔性事务，遵循BASE理论，最终一致性

与刚性事务不同，柔性事务允许一定时间内，不同节点的数据不一致，单要求最终一致。

 ![img](https://gimg2.baidu.com/image_search/src=http%3A%2F%2Fblog.gqylpy.com%2Fmedia%2Fai%2F2019-09%2Fb49725cd-dd86-4892-8c97-d610e746160f.png&refer=http%3A%2F%2Fblog.gqylpy.com&app=2002&size=f9999,10000&q=a80&n=0&g=0n&fmt=jpeg?sec=1626142872&t=8f9bd0095390b653661be3453d9b9228) 

第一阶段：prepare行为，调用自定义的prepare逻辑准备数据

第二阶段：commit行为，调用自定义的commit逻辑

第三阶段rollback行为，调用自定义的rollback逻辑

所谓TCC模式，是支持把自定义的分支事务纳入到全局事务管理中

##### 3.柔性事务-最大努力通知方案

最大努力通知型( Best-effort delivery)是最简单的一种柔性事务，适用于一些最终一致性时间敏感度低的业务，且被动方处理结果 不影响主动方的处理结果。典型的使用场景：如银行通知、商户通知等。最大努力通知型的实现方案，一般符合以下特点：

  1、不可靠消息：业务活动主动方，在完成业务处理之后，向业务活动的被动方发送消息，直到通知N次后不再通知，允许消息丢失(不可靠消息)。

  2、定期校对：业务活动的被动方，根据定时策略，向业务活动主动方查询(主动方提供查询接口)，恢复丢失的业务消息。

##### 4.柔性事务-可靠消息+最终一致方案（异步确保型）

业务逻辑在业务事务提交之时，向实时消息服务请求发送消息，实时消息服务只记录消息数据，而不是真正的发送。业务处理服务在业务提交之后，向实时消息服务确认发送。只有在得到确认发送指令后，实时消息服务才会真正发送。

#### 三.使用seata解决分布式事务问题（2PC)

seata中文官网：http://seata.io/zh-cn/docs/user/quickstart.html

TC (Transaction Coordinator) - 事务协调者

维护全局和分支事务的状态，驱动全局事务提交或回滚。

TM (Transaction Manager) - 事务管理器

定义全局事务的范围：开始全局事务、提交或回滚全局事务。

RM (Resource Manager) - 资源管理器

管理分支事务处理的资源，与TC交谈以注册分支事务和报告分支事务的状态，并驱动分支事务提交或回滚。

 ![img](http://seata.io/img/solution.png) 

```txt
Seata控制分布式事务
1. 每一个微服务必须创建undo_log
2. 安装事务协调器：https://github.com/seata/seata/releases    1.0.0版本     
3. 解压并启动seata-server        
	registry.conf 注册中心配置,修改registry type=nacos
4. 每个想要使用分布式事务的微服务都要用seata DataSourceProxy代理自己的数据源
5. 每个微服务都不必须导入        
	修改file.conf：vgroup_mapping.{当前应用的名字}-fescar-service-group          
6. 给分布式大事务入口标注 @GlobalTransactional       
7. 每一个远程的小事务用 @Transactional
8. 启动测试分布式事务
```

导入依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
</dependency>
```

环境搭建

下载senta-server-0.7.1并修改`register.conf`,使用nacos作为注册中心

```shell
registry {
  # file 、nacos 、eureka、redis、zk、consul、etcd3、sofa
  type = "nacos"

  nacos {
    serverAddr = "#:8848"
    namespace = "public"
    cluster = "default"
  }
```

将`register.conf`和`file.conf`复制到需要开启分布式事务的根目录，并修改`file.conf`

 `vgroup_mapping.${application.name}-fescar-service-group = "default"`

```shell
service {
  #vgroup->rgroup
  vgroup_mapping.mall-ware-fescar-service-group = "default"
  #only support single node
  default.grouplist = "127.0.0.1:8091"
  #degrade current not support
  enableDegrade = false
  #disable
  disable = false
  #unit ms,s,m,h,d represents milliseconds, seconds, minutes, hours, days, default permanent
  max.commit.retry.timeout = "-1"
  max.rollback.retry.timeout = "-1"
}
```

使用seata包装数据源

```java
@Configuration
public class MySeataConfig {
    @Autowired
    DataSourceProperties dataSourceProperties;

    @Bean
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {

        HikariDataSource dataSource = dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        if (StringUtils.hasText(dataSourceProperties.getName())) {
            dataSource.setPoolName(dataSourceProperties.getName());
        }
        return new DataSourceProxy(dataSource);
    }
}
```

在大事务的入口标记注解`@GlobalTransactional`开启全局事务，并且每个小事务标记注解`@Transactional`

```java
@GlobalTransactional
@Transactional
@Override
public SubmitOrderResponseVo submitOrder(OrderSubmitVo submitVo) {
}
```

### 
