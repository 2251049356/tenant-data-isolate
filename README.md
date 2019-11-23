# tenant-data-isolate
多租户数据隔离解决方案
## 简介
tenant-data-isolate是多租户架构中，数据级隔离的解决方案。基于mybatis拦截器实现，并兼容mybatis-pagehelper。要求每条数据拥有租户标识字段并每次请求提供租户标识字段，
tenant-data-isolate会自动为CRUD操作维护租户标识字段。
## 对比
Mybatis Plus也提供了数据隔离级的方案 [多租户 SQL 解析器](https://mp.baomidou.com/guide/tenant.html)。它的做法是数据与租户表进行关联，然后在where子句中注入具体租户实现。  
tenant-data-isolate则为每条数据都维护租户标识，以简化sql语句，同时不需要人工维护租户信息。
## 不足
更新操作没有处理，必须根据主键更新或自行处理  
租户条件添加到where子句的开始，影响查询速度
## 使用
目前在spring cloud项目上实践。  
1、引入依赖  
2、创建业务数据表时加入字段：id_tenant varchar(100)。注意：最好是为id_tenant创建索引。  
3、使用mybatis tenant拦截器  
`@Bean
TenantMybatisInterceptor getTenantMybatisInterceptor(){
  return new TenantMybatisInterceptor();
}`  
4、在数据处理服务上拦截请求，并根据请求信息设置HTTP Header或Session的TENANT_FLAG属性，Session优先级要大于Header。  
*5、对于联表表查询，如果id_tenant字段非一张表独有，则在mapper的api上添加@TenantField(field="xxx.id_tenant")，其中xxx为表名或表别名  
*6、不希望某些mapper api加入租户控制，在mapper api上添加@TenantIgnore  
*7、维持Feign调用的租户标识  
`@Bean
FeignRequestTenantHeaderInterceptor getFeignRequestTenantHeaderInterceptor(){
  return new FeignRequestTenantHeaderInterceptor();
}`