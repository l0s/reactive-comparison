/**
 * Copyright © 2020 Carlos Macasaet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package async;

import static org.springframework.hateoas.config.EnableHypermediaSupport.HypermediaType.HAL;
import static org.springframework.hateoas.support.WebStack.WEBMVC;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.hateoas.config.EnableHypermediaSupport;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import jdbc.config.JdbcConfiguration;

@Configuration
@Import(value = {JdbcConfiguration.class})
@ComponentScan({"async"})
@EnableHypermediaSupport(stacks = WEBMVC, type = HAL)
public class ApplicationConfiguration implements AsyncConfigurer {

    private int threadPoolSize = 4;

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ForwardedHeaderTransformer forwardedHeaderTransformer() {
        return new ForwardedHeaderTransformer();
    }

    @Bean
    public WebServerFactory webServerFactory() {
        /*
         * I'm observing weird latency issues with Tomcat when the max
         * connection count is less than 32. For example, POST requests to
         * create users were taking 3.5 minutes.
         */
        return new UndertowServletWebServerFactory();
//        return new TomcatServletWebServerFactory();
    }

    public Executor getAsyncExecutor() {
        return new ThreadPoolExecutor(getThreadPoolSize(), getThreadPoolSize(), 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
    }

    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncUncaughtExceptionHandler() {

            private final Logger logger = LoggerFactory.getLogger(getClass());

            public void handleUncaughtException(final Throwable ex, final Method method, final Object... params) {
                logger.error(ex.getMessage(), ex);
            }
        };
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    @Value("${executor.threadPoolSize:4}")
    public void setThreadPoolSize(final int threadPoolSize) {
        if (threadPoolSize < 1) {
            throw new IllegalArgumentException("Invalid therad pool size: " + threadPoolSize);
        }
        this.threadPoolSize = threadPoolSize;
    }

}