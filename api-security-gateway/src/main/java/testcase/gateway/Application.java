package testcase.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.EnableOAuth2Sso;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.oauth2.provider.token.ResourceServerTokenServices;
import org.springframework.stereotype.Component;

@SpringBootApplication
@EnableZuulProxy
public class Application {
	
	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Component
	@EnableOAuth2Sso
	public static class OAuth2WebSecurityConfiguration extends WebSecurityConfigurerAdapter {

		@Value(value = "${checkTokenUrl}")
		private String checkTokenEndpointURL;
		
		@Bean
		public ResourceServerTokenServices userInfoTokenServices() {
			CustomRemoteTokenServices services = new CustomRemoteTokenServices();
			services.setCheckTokenEndpointUrl(checkTokenEndpointURL);
			return services;
		}
		
		/**
		 * Define the security that applies to the proxy
		 */
		
		@Override
	    public void configure(HttpSecurity http) throws Exception {
			http.antMatcher("/**")
		      .authorizeRequests()
		        .anyRequest().authenticated()
		        // CSRF disabled for testcase simplicity
	            .and().csrf().disable();
		
		}
	}	
}
