package com.ruoyi.gateway.handler;

import java.io.IOException;
import java.util.Objects;

import com.ruoyi.common.core.utils.uuid.IdUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import com.ruoyi.common.core.exception.CaptchaException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.gateway.service.ValidateCodeService;
import reactor.core.publisher.Mono;

/**
 * 验证码获取
 *
 * @author ruoyi
 */
@Component
public class ValidateCodeHandler implements HandlerFunction<ServerResponse>
{
    @Autowired
    private ValidateCodeService validateCodeService;

    @Override
    public Mono<ServerResponse> handle(ServerRequest serverRequest)
    {
        AjaxResult ajax;
        try
        {
            switch (serverRequest.queryParam("type").orElse("image")) {
                case "sms":
                    if (Objects.nonNull(serverRequest.queryParam("receiver").orElse(null))) {
                        ajax = validateCodeService.createSMSCaptcha(
                                serverRequest.queryParam("receiver").orElse(null),
                                serverRequest.queryParam("uuid").orElse(IdUtils.simpleUUID())
                        );
                        break;
                    }
                default:
                    ajax = validateCodeService.createCaptcha();
            }
        }
        catch (CaptchaException | IOException e)
        {
            return Mono.error(e);
        }
        return ServerResponse.status(HttpStatus.OK).body(BodyInserters.fromValue(ajax));
    }
}
