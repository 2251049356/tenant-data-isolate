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

import com.tenant.dataisolate.util.Constants.Tenant;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * feign请求的头部租户标识设置
 *
 * @author WanSY
 **/
public class FeignRequestTenantHeaderInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate requestTemplate) {
        // 获取租户标识
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        // 从header中获取
        String tenantFlag = null;
        String tenantFlagHeader = request.getHeader(Tenant.FLAG);
        // 从session中取，session优先级高于header
        String tenantFlagSession = null;
        HttpSession session = request.getSession();
        if (session != null) {
            tenantFlagSession = (String) session.getAttribute(Tenant.FLAG);
        }
        tenantFlag = tenantFlagSession == null ? tenantFlagHeader : tenantFlagSession;
        // 设置租户标识
        if (!StringUtils.isEmpty(tenantFlag)) {
            requestTemplate.header(Tenant.FLAG, tenantFlag);
        }
    }
}
