package testcase.oauth2server;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.config.annotation.configurers.ClientDetailsServiceConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configuration.AuthorizationServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerSecurityConfigurer;

@Configuration
@EnableAuthorizationServer
public class OAuthConfiguration extends AuthorizationServerConfigurerAdapter {

	@Override
	public void configure(ClientDetailsServiceConfigurer clients)
			throws Exception {
	
		clients.inMemory()
					.withClient("poc-spa-client")		
					.authorizedGrantTypes("authorization_code","refresh_token")
					.secret("poc-spa-client")
					.authorities("ROLE_USER")
			    	/** OPTIONAL! But interesting!
			    	 * You can define N different ResourceServer and assign a resourceId so you 
			    	 * can specify that a client has access to a particular ResourceServer or
			    	 * several. This is useful if you want to publish several APIs */  
					// .resourceIds("apis")
					.scopes("api_access","isMemberOf","mail","givenName","uid")
					// TODO: Avoid this hardcode (the best way is to add clients using a database script
					// Commented to try with docker.. put this in Database .redirectUris("http://localhost:8765")
					.autoApprove(true)
					.accessTokenValiditySeconds(4);
	}	
	
	@Override
	public void configure(AuthorizationServerSecurityConfigurer oauthServer) throws Exception
	{
	   // check access endpoint is disabled by default (better isAuthenticated() in real scenarios)
	   oauthServer.checkTokenAccess("permitAll()");    
	}	
}