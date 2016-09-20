package testcase.gateway;

import javax.servlet.Filter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.security.oauth2.client.EnableOAuth2Sso;
import org.springframework.boot.context.embedded.FilterRegistrationBean;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.oauth2.provider.token.ResourceServerTokenServices;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.RequestContextFilter;

@SpringBootApplication
@EnableZuulProxy
@Import(RedisHttpSessionTimeoutConfiguration.class)
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
		
		// Required because of:
		// http://stackoverflow.com/questions/30976624/oauth2clientcontext-spring-security-oauth2-not-persisted-in-redis-when-using-s
		@Bean
		@ConditionalOnMissingBean(RequestContextFilter.class)
		public RequestContextFilter requestContextFilter() {
			return new RequestContextFilter();
		}

		@Bean
		public FilterRegistrationBean requestContextFilterChainRegistration(
				@Qualifier("requestContextFilter") Filter securityFilter) {
			FilterRegistrationBean registration = new FilterRegistrationBean(securityFilter);
			registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
			registration.setName("requestContextFilter");
			return registration;
		}

	    // Required to customize Spring Session so the domain name is properly set in the user session cookie
	    // The following regular expression pattern would consider the last two parts of the domain (so this works with subdomains properly)
	    // but it will not specify domain names when the expression is not matched (and thus working in local development environments where
	    // you might not be working with FQDN for proxy server (remember however that OpenAM always requires FQDN)
	    // @see org.springframework.session.web.http.DefaultCookieSerializer#setDomainNamePattern(String)
	    // This is necessary at lest for this PoC as there can be different instances of the proxy (API gateway) and they can be accessed
	    // through different domains. Ex: proxy.openam.com and proxy2.openam.com. 
	    // This might not be necessary in a production-like environment where the different instances of the proxy are behind a load balancer
	    // and thus they are access always from the same domain. Then default spring session setup should be enough.
	    @Bean
	    public CookieSerializer cookieSerializer() {
	    	DefaultCookieSerializer serializer = new DefaultCookieSerializer();
            serializer.setCookiePath("/");
	    	serializer.setDomainNamePattern("^.+?\\.(\\w+\\.[a-z]+)$"); 
	    	return serializer;
	    }		
	}	
}
