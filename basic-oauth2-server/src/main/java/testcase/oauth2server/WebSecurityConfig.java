package testcase.oauth2server;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

@Configuration
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {
	@Override
	protected void configure(HttpSecurity http) throws Exception {
		// http.httpBasic();
		http.csrf().disable().authorizeRequests().anyRequest().authenticated().and()
				.formLogin().permitAll()
				.and().logout().permitAll();
	}

}
