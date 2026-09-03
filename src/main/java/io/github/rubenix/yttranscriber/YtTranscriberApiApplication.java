package io.github.rubenix.yttranscriber;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class YtTranscriberApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(YtTranscriberApiApplication.class, args);
	}

}
