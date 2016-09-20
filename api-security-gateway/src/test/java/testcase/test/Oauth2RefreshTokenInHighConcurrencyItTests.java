package testcase.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.OutputCapture;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.boot.test.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;


/**
 * Necessary to have httpclient for this tests (with scope tests) so that the http returns are handle correctly.
 * Otherwise you will experience exactly the problem described here:
 * http://stackoverflow.com/questions/27341604/exception-when-using-testresttemplate 
 */
@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@IntegrationTest("server.port=0")
@SpringApplicationConfiguration(classes = Application.class)
public class Oauth2RefreshTokenInHighConcurrencyItTests {
	
	@Rule
	public OutputCapture capture = new OutputCapture();	
	
	@Value("${oauth2.authserver.baseUrl:}")
	protected String authServerBaseUrl;
	
	protected int port;
		
	@Autowired
	private EmbeddedWebApplicationContext server;	
		
	RestTemplate restTemplate = new TestRestTemplate();
	
	private String authorizationCode=null;	
	private String oauth2ServerSessionId=null;
	private String apiGatewaySessionId=null;	
	
	@Before
	public void init(){
		 port = server.getEmbeddedServletContainer().getPort();
		 if (authServerBaseUrl.isEmpty()){
			 authServerBaseUrl = "http://localhost:" + port;
		 }
		 userLoginInOAuth2Server();
	}
	
	@Test	
	public void concurrencyTestRefreshingOAuth2Token() throws Exception {
		
		loginInAPIGateway();	
		
		// Wait 10 secs to ensure the token is expired (it lasts 4 seconds)
		Thread.sleep(10000);
		
		// Run several concurrent threads accessing oauth2 protected resource /me
		final int MYTHREADS = 50;		 
		ExecutorService executor = Executors.newFixedThreadPool(MYTHREADS);
		for (int i = 0; i < 50; i++) {
			Runnable worker = new RunnableCalltoMeResource();
			executor.execute(worker);
		}
		executor.shutdown();
		// Wait until all threads are finish
		while (!executor.isTerminated()) {

		}
		
		System.out.println("\nFinished all threads");
	}

	
	public class RunnableCalltoMeResource implements Runnable {
		
		UriComponentsBuilder builder = null;
		HttpEntity<String> entity = null;
		
		RunnableCalltoMeResource() {
			builder = UriComponentsBuilder.fromHttpUrl("http://localhost:8765" + "/me");
	        HttpHeaders headers = new HttpHeaders();
	        headers.set(HttpHeaders.COOKIE, apiGatewaySessionId);        
	        entity = new HttpEntity<String>("",headers);	                
		}

		@Override
		public void run() {			
			ResponseEntity<String> result2 = restTemplate.exchange(builder.build().encode().toUri(), HttpMethod.GET, entity, String.class);
			System.out.println("/me resulted in: " + result2.getStatusCode());
		}
	}	
		
	private void userLoginInOAuth2Server(){
		// Post username and password and keep the OAuth2 server returned session id once the user is logged in
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(authServerBaseUrl + "/login");
		HttpHeaders headers = new HttpHeaders();
		MultiValueMap<String, String> map= new LinkedMultiValueMap<String, String>();
		map.add("username", "user");
		map.add("password", "password");
		HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<MultiValueMap<String, String>>(map, headers);		
		ResponseEntity<String> result2 = restTemplate.exchange(builder.build().encode().toUri(), HttpMethod.POST, entity, String.class);
		// Here we will be redirected to "hello" page after login but we just want to keep the cookies
		assertEquals(HttpStatus.FOUND, result2.getStatusCode());
		// In this test case we can just get the first cookie as we know there is just one (the session id)
		oauth2ServerSessionId = result2.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);		
	}
	
	private void loginInAPIGateway(){
		// Post username and password and keep the OAuth2 server returned session id once the user is logged in
		
		// We call /login in API Gateway and we need to pass the session id cookie of the user authenticated in the AS
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl("http://localhost:8765" + "/login");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, oauth2ServerSessionId);        
        HttpEntity<String> entity = new HttpEntity<String>("",headers);
        ResponseEntity<String> result2 = restTemplate.exchange(builder.build().encode().toUri(), HttpMethod.GET, entity, String.class);        

        // This will provide a redirection to the /authorize endpoint in the AS to obtain an authorization code
        assertEquals(HttpStatus.FOUND, result2.getStatusCode());
        
        List<String> apiGatewayCookies = result2.getHeaders().get(HttpHeaders.SET_COOKIE);
        
        // We obtain the authorization code following the redirection, we need the oauth2ServerSessionId cookie        
        result2 = restTemplate.exchange(result2.getHeaders().getLocation(), HttpMethod.GET, entity, String.class);
        

        // Obtain the token from redirection URL after #
        URI location = result2.getHeaders().getLocation();
        authorizationCode = extractAuthorizationCode(location.toString());
        String state = extractState(location.toString());
        assertNotNull(authorizationCode);
        assertNotNull(state);

        // Call proxy /login passing the authorization code we have obtained in previous step
		builder = UriComponentsBuilder.fromHttpUrl("http://localhost:8765" + "/login");
		builder.queryParam("code", authorizationCode);
		builder.queryParam("state", state);
		for (String cookie : apiGatewayCookies){
			headers.add(HttpHeaders.COOKIE, cookie);
		}
        entity = new HttpEntity<String>("",headers);
        result2 = restTemplate.exchange(builder.build().encode().toUri(), HttpMethod.GET, entity, String.class);        
        
        // We don't care for the redirection here, we just want the API Gateway session id which we need to use for the rest of the test
        assertEquals(HttpStatus.FOUND, result2.getStatusCode());
        apiGatewaySessionId = result2.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);        
	}
	
	protected String extractAuthorizationCode(String urlFragment){
		return extractParam(urlFragment, "code");
	}
	
	protected String extractState(String urlFragment){
		return extractParam(urlFragment, "state");
	}	

	protected String extractParam(String urlFragment, String paramName){
        String[] fragmentParams = urlFragment.split("&");
        String extractedParam=null;
        for (String param : fragmentParams){
        	if (param.contains(paramName)){
        		String[] params = param.split("=");
        		extractedParam = params[1];
        		break;
        	}
        }
        return extractedParam;
	}	
	
}

