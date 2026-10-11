package com.fruit.shop.support;

import com.fruit.shop.support.mapper.HealthCheckMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脚手架冒烟测试：证明 Spring Boot + MyBatis-Plus + H2 + OpenAPI 依赖组合可用。
 * 这条测试挂掉说明环境或依赖有问题，后面的业务开发不用再排查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootstrapSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private HealthCheckMapper healthCheckMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void mapperCanReadSeedRow() {
        assertThat(healthCheckMapper.selectList(null))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo("OK"));
    }

    @Test
    void healthEndpointResponds() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/health", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("fruit-shop").contains("UP");
    }
}
