package dev.trakr.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// BLOCK 1: the whole app on a random port, plus MockMvc
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiApplicationTests {

    @Autowired
    MockMvcTester mvc;

    // BLOCK 2: the context starts
    @Test
    void contextLoads() {
    }

    // BLOCK 3: actuator health is UP
    @Test
    void healthIsUp() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }
}
