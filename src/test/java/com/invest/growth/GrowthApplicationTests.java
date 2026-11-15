package com.invest.growth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"OPENAI_API_KEY=test", "KITE_API_KEY=test", "KITE_API_SECRET=test",
		"ingestion.fixed-rate-ms=3600000",
		"spring.sql.init.mode=never"
})
class GrowthApplicationTests {

	@Test
	void contextLoads() {
	}

}
