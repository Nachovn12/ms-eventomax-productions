package cl.duoc.eventomax.productions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@SpringBootTest
@ActiveProfiles("test")
class MsEventomaxProductionsApplicationTests {

    @MockitoBean
    JwtDecoder jwtDecoder;

	@Test
	void contextLoads() {
	}

}
