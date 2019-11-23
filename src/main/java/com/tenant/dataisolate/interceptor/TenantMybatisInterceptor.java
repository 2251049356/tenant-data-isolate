/*
 * Copyright (c) 2011-2020, WanSY.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.tenant.dataisolate.interceptor;

import com.tenant.dataisolate.annotation.TenantConditionField;
import com.tenant.dataisolate.annotation.TenantIgnore;
import com.tenant.dataisolate.sqlsource.StaticSqlSource;
import com.tenant.dataisolate.util.Constants.Tenant;
import java.lang.reflect.Method;
import java.util.Properties;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.MappedStatement.Builder;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * @author WanSY
 * @date 2019/9/24 16:42
 **/
@Intercepts(value = { @Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class,
        RowBounds.class, ResultHandler.class }),
        @Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class }),
        @Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class }) })
public class TenantMybatisInterceptor implements Interceptor {

    private static final String DEFAULT_TENANT_COL = "id_tenant";

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 拦截逻辑
        // 多租户拦截插入和查询
        // 如果http头部没有租户标识则不拦截
        RequestAttributes contextHolder = RequestContextHolder.getRequestAttributes();
        if (contextHolder != null) {
            HttpServletRequest request = ((ServletRequestAttributes) contextHolder).getRequest();
            String tenantFlag = request.getHeader(Tenant.FLAG);
            if (!StringUtils.isEmpty(tenantFlag)) {
                MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
                // 获取mapper层调用的方法
                //TODO 因为这里不能直接获取，所以解决办法基于mybatis不允许重载，即每个方法的名是唯一的
                Method method = null;
                String statementId = mappedStatement.getId();
                int classMethodDot = statementId.lastIndexOf('.');
                Class clazz = Class.forName(statementId.substring(0, classMethodDot));
                String methodName = statementId.substring(classMethodDot + 1);
                // 是否需要处理：1、非mybatis的操作 2、非TenantIgnore的操作
                boolean needDeal = !methodName.contains("!"); // 带!的api是mybatis的特殊操作
                // 如果接口声明了是非多租户接口，也不拦截
                if (needDeal) {
                    // 如果方法名称以_COUNT结尾，这个是PageHelper的方法，需要获取到真正的方法
                    methodName = methodName.replace("_COUNT", "");
                    Method[] methods = clazz.getDeclaredMethods();
                    for (Method methodTmp : methods) {
                        if (methodName.equals(methodTmp.getName())) {
                            method = methodTmp;
                            break;
                        }
                    }
                    TenantIgnore ignore = method == null ? null : method.getAnnotation(TenantIgnore.class);
                    needDeal = ignore == null;
                }
                if (needDeal) {
                    BoundSql boundSql = invocation.getArgs().length == 6
                            ? (BoundSql) invocation.getArgs()[5]
                            : mappedStatement.getBoundSql((Object) invocation.getArgs()[1]);
                    SqlCommandType sqlType = mappedStatement.getSqlCommandType();
                    String newSql = null;
                    if (sqlType == SqlCommandType.INSERT) {
                        // 处理插入，每条记录增加租户字段值
                        newSql = dealInsert(boundSql.getSql(), tenantFlag);
                    } else if (sqlType == SqlCommandType.SELECT) {
                        // 处理查询，在where子句后新增租户条件
                        newSql = dealSelect(boundSql.getSql(), method, tenantFlag);
                    } else if (sqlType == SqlCommandType.DELETE) {
                        // 处理删除
                        newSql = dealDelete(boundSql.getSql(), tenantFlag);
                    }
                    //TODO update规定只能根据主键改
                    if (!StringUtils.isEmpty(newSql)) {
                        MetaObject boundSqlMeta = SystemMetaObject.forObject(boundSql);
                        boundSqlMeta.setValue("sql", newSql);
                        // pagehelper生成的cachekey不正确，缓存查询注入了boundSql，这个sql的优先级最高且不走缓存
                        if (invocation.getArgs().length == 6) {
                            invocation.getArgs()[5] = boundSql;
                        }
                        // 对于不直接执行boundsql的语句则把新的sql注入到mappedstatement
                        else {
                            MappedStatement newMapperStatement = new Builder(mappedStatement.getConfiguration(),
                                    mappedStatement.getId(), new StaticSqlSource(boundSql),
                                    mappedStatement.getSqlCommandType()).cache(mappedStatement.getCache())
                                    .databaseId(mappedStatement.getDatabaseId())
                                    .fetchSize(mappedStatement.getFetchSize())
                                    .flushCacheRequired(mappedStatement.isFlushCacheRequired())
                                    .keyGenerator(mappedStatement.getKeyGenerator()).lang(mappedStatement.getLang())
                                    .parameterMap(mappedStatement.getParameterMap())
                                    .resource(mappedStatement.getResource()).resultMaps(mappedStatement.getResultMaps())
                                    .resultOrdered(mappedStatement.isResultOrdered())
                                    .resultSetType(mappedStatement.getResultSetType())
                                    .statementType(mappedStatement.getStatementType())
                                    .timeout(mappedStatement.getTimeout()).useCache(mappedStatement.isUseCache())
                                    .build();
                            invocation.getArgs()[0] = newMapperStatement;
                        }
                    }
                }
            }
        }
        return invocation.proceed();
    }

    /**
     * 处理删除
     *
     * @param sql
     * @param tenantFlag
     * @return
     */
    private String dealDelete(String sql, String tenantFlag) {
        int whereIdx = strIndexOfIgnoreCase(sql, "where");
        if (whereIdx == -1) {
            sql += " where " + DEFAULT_TENANT_COL + "='" + tenantFlag + "'";
        } else {
            sql += " and " + DEFAULT_TENANT_COL + "='" + tenantFlag + "'";
        }
        return sql;
    }

    /**
     * 忽略大小写查找
     *
     * @param str
     * @param key
     * @return
     */
    private int strIndexOfIgnoreCase(String str, String key) {
        int retval = str.indexOf(key);
        retval = retval == -1 ? str.indexOf(key.toUpperCase()) : retval;
        retval = retval == -1 ? str.indexOf(key.toLowerCase()) : retval;
        return retval;
    }

    /**
     * 处理查询
     *
     * @param sql
     * @param method
     * @param tenantFlag
     */
    private String dealSelect(String sql, Method method, String tenantFlag) {
        StringBuilder selectSql = new StringBuilder(sql);
        String retval;
        // 获取条件字段名
        String columnName = DEFAULT_TENANT_COL;
        TenantConditionField tenantColumn = method == null ? null : method.getAnnotation(TenantConditionField.class);
        if (tenantColumn != null) {
            columnName = tenantColumn.field();
        }
        // 找到where子句位置
        int whereIdx = strIndexOfIgnoreCase(selectSql.toString(), "where");
        if (whereIdx == -1) {
            int groupIdx = strIndexOfIgnoreCase(selectSql.toString(), "group");
            whereIdx = groupIdx == -1 ? strIndexOfIgnoreCase(selectSql.toString(), "order") : groupIdx;
            whereIdx = whereIdx == -1 ? strIndexOfIgnoreCase(selectSql.toString(), "limit") : whereIdx;
            whereIdx = whereIdx == -1 ? selectSql.length() : whereIdx;
            selectSql.insert(whereIdx, " where " + columnName + "='" + tenantFlag + "'");
            whereIdx += 1;
        } else {
            selectSql.insert(whereIdx + "where ".length(), columnName + "='" + tenantFlag + "' and ");
        }
        return selectSql.toString();
    }

    /**
     * 处理插入
     *
     * @param sql
     * @param tenantFlag
     * @return
     */
    private String dealInsert(String sql, String tenantFlag) {
        // 如果插入带了详细的字段信息，则在字段列表最后添加
        // 如果插入没带字段列表，不允许
        // TODO 只支持mysql
        String oldSql = sql;
        // 去除所有的空白符
        oldSql = oldSql.replace("\n", " ").replace("\r", " ").replaceAll("\\s+", " ").replace(") , (", "),(");
        StringBuilder insertSql = new StringBuilder(oldSql);
        int valuesIdx = strIndexOfIgnoreCase(insertSql.toString(), "values");
        if (valuesIdx == -1) {
            throw new RuntimeException("插入租户关联异常。插入语句不允许使用子查询");
        }
        int colIdx = insertSql.lastIndexOf(")", valuesIdx);
        if (colIdx == -1) {
            throw new RuntimeException("插入租户关联异常。插入语句必须带字段列表");
        }
        // 设置值
        int valueIdx = insertSql.lastIndexOf(")");
        while (valueIdx != -1) {
            insertSql.insert(valueIdx, ",'" + tenantFlag + "'");
            valueIdx = insertSql.lastIndexOf("),(", valueIdx);
        }
        // 设置字段
        insertSql.insert(colIdx, "," + DEFAULT_TENANT_COL);
        return insertSql.toString();
    }

    @Override
    public Object plugin(Object target) {
        // 是否需要拦截
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {
    }
}
