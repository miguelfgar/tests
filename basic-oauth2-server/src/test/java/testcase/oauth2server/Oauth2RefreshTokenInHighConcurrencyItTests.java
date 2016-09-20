package testcase.oauth2server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.context.embedded.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.oauth2.client.resource.UserRedirectRequiredException;
import org.springframework.security.oauth2.client.test.BeforeOAuth2Context;
import org.springframework.security.oauth2.client.test.OAuth2ContextConfiguration;
import org.springframework.security.oauth2.client.test.OAuth2ContextSetup;
import org.springframework.security.oauth2.client.test.RestTemplateHolder;
import org.springframework.security.oauth2.client.token.AccessTokenRequest;
import org.springframework.security.oauth2.client.token.grant.code.AuthorizationCodeResourceDetails;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestOperations;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Necessary to have httpclient for this tests (with scope tests) so that the http returns
 * are handle correctly. Otherwise you will experience exactly the problem described here:
 * http://stackoverflow.com/questions/27341604/exception-when-using-testresttemplate
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment=WebEnvironment.RANDOM_PORT)
public class Oauth2RefreshTokenInHighConcurrencyItTests implements RestTemplateHolder {

	private String authServerBaseUrl;

	@LocalServerPort
	private int port;

	private RestOperations restTemplate;

	@Rule
	public OAuth2ContextSetup context = OAuth2ContextSetup.standard(this);

	private String cookie;

	@BeforeOAuth2Context
	public void loginAndExtractCookie() {
		authServerBaseUrl = "http://localhost:" + port;
		this.cookie = loginAndGrabCookie();
	}

	@Test
	@OAuth2ContextConfiguration(resource = MyClient.class, initialize = false)
	public void concurrencyTestRefreshingOAuth2Token() throws Exception {

		approveAccessTokenGrant("http://localhost:" + port + "/");

		assertEquals(HttpStatus.OK,
				restTemplate
						.getForEntity("http://localhost:" + port + "/me", String.class)
						.getStatusCode());

		// Wait 10 secs to ensure the token is expired (it lasts 4 seconds)
		// Thread.sleep(10000);

		// Run several concurrent threads accessing oauth2 protected resource /me
		final int MYTHREADS = 1;
		ExecutorService executor = Executors.newFixedThreadPool(MYTHREADS);
		List<Future<?>> tasks = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			Runnable worker = new RunnableCalltoMeResource();
			tasks.add(executor.submit(worker));
		}
		executor.shutdown();
		// Wait until all threads are finish
		for (Future<?> task : tasks) {
			task.get();
		}
		System.out.println("\nFinished all threads");
	}

	private String loginAndGrabCookie() {

		TestRestTemplate template = new TestRestTemplate();

		MultiValueMap<String, String> formData;
		formData = new LinkedMultiValueMap<String, String>();
		formData.add("username", "user");
		formData.add("password", "password");

		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Arrays.asList(MediaType.TEXT_HTML));
		ResponseEntity<Void> result = template.exchange(authServerBaseUrl + "/login",
				HttpMethod.POST,
				new HttpEntity<MultiValueMap<String, String>>(formData, headers),
				Void.class);
		assertEquals(HttpStatus.FOUND, result.getStatusCode());
		String cookie = result.getHeaders().getFirst("Set-Cookie");

		assertNotNull("Expected cookie in " + result.getHeaders(), cookie);

		return cookie;

	}

	protected static class MyClient extends AuthorizationCodeResourceDetails {
		private Oauth2RefreshTokenInHighConcurrencyItTests test;

		public MyClient(Object target) {
			super();
			test = (Oauth2RefreshTokenInHighConcurrencyItTests) target;
			setClientId("poc-spa-client");
			setClientSecret("poc-spa-client");
			setScope(Arrays.asList("api_access"));
			setId(getClientId());
		}

		@Override
		public String getUserAuthorizationUri() {
			return test.authServerBaseUrl + "/oauth/authorize";
		}

		@Override
		public String getAccessTokenUri() {
			return test.authServerBaseUrl + "/oauth/token";
		}
	}

	private class RunnableCalltoMeResource implements Runnable {

		UriComponentsBuilder builder = null;
		HttpEntity<String> entity = null;

		RunnableCalltoMeResource() {
			builder = UriComponentsBuilder.fromHttpUrl(authServerBaseUrl + "/me");
			HttpHeaders headers = new HttpHeaders();
			entity = new HttpEntity<String>("", headers);
		}

		@Override
		public void run() {
			ResponseEntity<String> result2 = restTemplate.exchange(
					builder.build().encode().toUri(), HttpMethod.GET, entity,
					String.class);
			System.out.println("/me resulted in: " + result2.getStatusCode());
		}
	}

	public void setRestTemplate(RestOperations restTemplate) {
		this.restTemplate = restTemplate;
	}

	public RestOperations getRestTemplate() {
		// TODO Auto-generated method stub
		return this.restTemplate;
	}

	protected String getBasicAuthentication() {
		return "Basic " + new String(Base64.encode((context.getResource().getClientId()
				+ ":" + context.getResource().getClientSecret()).getBytes()));
	}

	protected HttpHeaders getAuthenticatedHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Arrays.asList(MediaType.TEXT_HTML));
		headers.set("Authorization", getBasicAuthentication());
		if (context.getRestTemplate() != null) {
			context.getAccessTokenRequest().setHeaders(headers);
		}
		return headers;
	}

	protected String getAuthorizeUrl(String clientId, String redirectUri, String scope) {
		UriComponentsBuilder uri = UriComponentsBuilder
				.fromHttpUrl(authServerBaseUrl + "/oauth/authorize")
				.queryParam("response_type", "code").queryParam("state", "mystateid")
				.queryParam("scope", scope);
		if (clientId != null) {
			uri.queryParam("client_id", clientId);
		}
		if (redirectUri != null) {
			uri.queryParam("redirect_uri", redirectUri);
		}
		return uri.build().toString();
	}

	protected void approveAccessTokenGrant(String currentUri) {

		AccessTokenRequest request = context.getAccessTokenRequest();
		AuthorizationCodeResourceDetails resource = (AuthorizationCodeResourceDetails) context
				.getResource();

		request.setCookie(cookie);
		if (currentUri != null) {
			request.setCurrentUri(currentUri);
		}

		String location = null;

		try {
			// First try to obtain the access token...
			assertNotNull(context.getAccessToken());
			fail("Expected UserRedirectRequiredException");
		}
		catch (UserRedirectRequiredException e) {
			// Expected and necessary, so that the correct state is set up in the
			// request...
			location = e.getRedirectUri();
		}

		assertTrue(location.startsWith(resource.getUserAuthorizationUri()));
		assertNull(request.getAuthorizationCode());

	}

}
