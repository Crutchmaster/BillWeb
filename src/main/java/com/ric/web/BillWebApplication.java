package com.ric.web;

import javax.security.auth.message.config.AuthConfigFactory;

import org.apache.catalina.authenticator.jaspic.AuthConfigFactoryImpl;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationContext;


@SpringBootApplication
//@EnableCaching
public class BillWebApplication {

	private static ApplicationContext applicationContext = null;
	
	/*public static void main(String[] args) {
		SpringApplication.run(BillWebApplication.class, args);
	}*/
	
	public static void main(String[] args) {
		
        String mode = args != null && args.length > 0 ? args[0] : null;

		if (AuthConfigFactory.getFactory() == null) {
            AuthConfigFactory.setFactory(new AuthConfigFactoryImpl());
        }
        
        if (applicationContext != null && mode != null && "stop".equals(mode)) {
            System.exit(SpringApplication.exit(applicationContext, new ExitCodeGenerator() {
                @Override
                public int getExitCode() {
                    return 0;
                }
            }));
        }
        else {
            SpringApplication app = new SpringApplication(BillWebApplication.class);
            applicationContext = app.run(args);
        }
    }	
}
