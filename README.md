# Test Case project for https://github.com/spring-projects/spring-security-oauth/issues/834
This is a project to share a simple TestCase showing spring-oauth2 issue: https://github.com/spring-projects/spring-security-oauth/issues/834

## Structure of the project and modules
The project is compound of a **Maven multimodule project** with the following **structure**

-  root pom (artifactId: test-case-multimodule-project)
	- module api-security-gateway (artifactId: api-security-gateway)
	- module basic-oauth2-server: (artifactId: basic-oauth2-server)
	- module client-for-tests: (artifactId: client-for-tests)  
	
The artifact test-case-multimodule-project it only provides the structure of the Maven multimodule project, is just a "pom".

The other three projects contain:

- **basic-oauth2-server (PORT: 8088)**: 
	- Is a very basic OAuth2 server implemented with OAuth2 with only one clientID registered that uses "authorization_code" and "refresh" grant types
	- It provides an OAuth2 protected resource (so it is also a Resource Server) - In particular it provides an oauth2 protected endpoint /me providing information about the user associated to the oauth2 token
	- Starts a regular spring boot project
	- *IMPORTANT: You need to start this project before running the tests (in client-for-test project)*

- **api-security-gateway (PORT 8765)**:
	- A very basic security-gateway implemented using Zuul Proxy enabled with @EnableOAuth2SSO and config
	- It's configured with the only OAuth2 ClientId existing in the basic-oauth2-server (authorization_code and refresh grant types)
	- Starts a regular spring boot project
	- **IMPORTANT: You need to start this project before running the tests (in client-for-test project)**
	
- **client-for-tests**:
	- This project contains a @IntegrationTests called Oauth2RefreshTokenInHighConcurrencyItTests.java that you can run directly (once basic-oauth2-server and api-security-gateway have started up)
	
## What is the aim of this TestCase?

- **Concurrency for token refresh once it has expired is NOT controlled (for those flows that supporte refresh, at least "authorization_code" with refresh)** : several threads refresh the same oauth2 token
- The result of several threads refreshing the same oauth2 token depends on how the Authorization Server is configured. In this case (with a basic spring-oauth2 server implementation) the refresh token
  does not change so the result is that every thread gets a new access token (only one is kept in the user session) - thus generating unnecessary oauth2 access tokens in the authorization server.
  My experience with other Authorization Servers is even worst: some authorization servers allow the refresh token to change every time it is used and so only the first thread will be able to obtain a new access token - the
  rest will fail.
- Discussed here: https://github.com/spring-projects/spring-security-oauth/issues/834
	
## What does the test (Oauth2RefreshTokenInHighConcurrencyItTests.java) do?

- Simulates an http "client" that authenticates in the Authorization Server and accesses the /me oauth protected endpoint by means the security gateway (Zuul). 
- The client only accesses Zuul directly -> zuul routes to the /me oauth2 protected resource (served by the own authorization server)
- Zuul is in charge to handle oauth2 tokens for the test client, keep it in the user session and relay the token downstream (to the /me resource) every time a request linked to the user session is raised
- In order to reproduce the issue we need high concurrency so the test launches several threads accessing the "/me" protected resource when the oauth2 access token has expired 
  (in order to do so first it authenticates the user "user" with password "password" and it waits until the first access token expires). Is a spring @IntegrationTest annotation Test just to simulate a client accessing through Zuul proxy to the
  "/me" resource
	
## What is the result of the test?

- A copy of OAuth2RestTemplate (spring-oauth2 v. 2.0.10) has been included adding *log traces that make evident in the api-security-gateway logs(attention: not in the test log! the token refresh happens in the api-security-gateway) that
  make evident that several threads triggered by requests to access "/me" refresh the same access token almost at the same time once the token is expired. For the sake of the test, the access token is configured to live only 4 seconds). 
  Some of them read the same expired access token and refresh it in paral.lel	thus obtaining N access tokens
	
## How many variants (branches) of this test are provided?

- Three: one with the original OAuth2RestTemplate (the only thing added are the logs), one with a possible solution for OAuth2RestTemplate (modified), one that instead of using default HttpSession implementation 
  uses spring-session and Redis
- **Branch REFRESH_ISSUE_WITHOUT_FIX**: When you see the basic-oauth2-server logs you will see that concurrency for token refresh is NOT controlled and several threads succeeded to get a different access token 
  (only one depending on race conditions will be kept in the user session - OAuth2ClientContext)
- **Branch REFRESH_ISSUE_WITH_FIX**: When you see the basic-oauth2-server logs you will see that concurrency for token refresh is controlled and only the first thread suceeded to get an access token, 
  the next return the same one (they don't post to the AS to refresh the token)
- **Branch REFRESH_ISSUE_WITH_REDIS**: Here there is another problem. Is not just a thread concurrency problem, is that writes to Redis using Spring session to replace default HttpSession show a delay, 
  so doesn't matter that I synchronize threads that the result is the same.
  I tried with RedisFlushMode.INMEDIATE but I did't suceed. This might not be an issue (I hope), I guess there must be a way to make spring-session with a Redis backed HttpSession implementation for this scenario as this combination 
  seems to be common if you want to scale your API gateway easily with spring-session. Here I might just need help.. As I said, the problem here is not the aforementioned thread synchronization (concurrency) issue.
	
## Try it out in a browser also, if you like

- Before launching Oauth2RefreshTokenInHighConcurrencyItTests.java you can also try the api-security-gateway and basic-oauth2-server from the browser if you like
- The basic-oauth2-server is based on some simple spring examples, very similar but adding the /me
- So it provides a /login page as in the spring example, this means you can try to access the /me resource from the browser, if you are not logged in Zuul will route you to the AS, you can login and the you will access the /me resource
- You can try it out starting the basic-oauth2-server and api-security-gateway and accessing:
  http://localhost:8765/me -> You will be redirected to the login page here (AS): http://localhost:8088/login -> Enter user "user" and password "password" -> You will be granted access to the /me resource 
- Basically the same thing does the test (it logs-in once and it tries to access to /me through the security-gateway several times concurrently once the oauth2 access token has expired)

## Trying the branch REFRESH_ISSUE_WITH_REDIS (You need Redis!! Docker is a good option!):

- This branch requrires Redis in port 6379 by default (you can change the config in the api-security-gateway config file)
- You can use your own Redis if you have or you can start one in a Docker container quickly. Example: **docker run -p 6379:6379 redis:3.0.7**

## About possible solution in branch REFRESH_ISSUE_WITH_FIX

- It only adds a "synchronize" in a method in OAuth2RestTemplate (I guess this is the place to control concurrency as OAuth2RestTemplate delegates to the underlaying TokenProvider).
  It's easy to see this change in the commit of the particular branch.
- This synchronize only affect in cases of refresh as before calling the synchronized method there is a condition checking whether the access token is expired or not, so it's not blocking all threds just in case of refresh
- **Why I have not provided a pull request directly?** Even though this solution seems to work and not to have an effect in performance (only for the refresh which is the case to control) I would like to open a bit of a discussion about this so
  we can check together if this is a good implementation or not or there are better options (Java Lock, Atomic Reference,...) and if you agree with the place to apply the "lock"
  
## Tests results comparison (branches REFRESH_ISSUE_WITHOUT_FIX and REFRESH_ISSUE_WITH_FIX)  

- REFRESH_ISSUE_WITHOUT_FIX: We see several threads posting the Authorization Server to refresh the same token. Several the get a new access token. All this happen in less than 100ms:

'''
2016-09-20 11:14:36.525 DEBUG 9300 --- [nio-8765-exec-5] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.525 DEBUG 9300 --- [nio-8765-exec-5] o.s.s.oauth2.client.OAuth2RestTemplate   : #### RETRIEVED EXPIRED ACCESS TOKEN FROM OAuth2ClientContext: a457ac1d-c486-43f2-9198-542ea0b42352 REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.525 DEBUG 9300 --- [nio-8765-exec-5] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.528 DEBUG 9300 --- [nio-8765-exec-5] g.c.AuthorizationCodeAccessTokenProvider : Retrieving token from http://localhost:8088/oauth/token
2016-09-20 11:14:36.532 DEBUG 9300 --- [nio-8765-exec-5] g.c.AuthorizationCodeAccessTokenProvider : Encoding and sending form: {grant_type=[refresh_token], refresh_token=[408dd3af-5c85-4e52-9631-009d637fc0cc]}
2016-09-20 11:14:36.547 DEBUG 9300 --- [nio-8765-exec-4] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.548 DEBUG 9300 --- [nio-8765-exec-6] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.552 DEBUG 9300 --- [nio-8765-exec-3] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.552 DEBUG 9300 --- [nio-8765-exec-4] o.s.s.oauth2.client.OAuth2RestTemplate   : #### RETRIEVED EXPIRED ACCESS TOKEN FROM OAuth2ClientContext: a457ac1d-c486-43f2-9198-542ea0b42352 REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.552 DEBUG 9300 --- [nio-8765-exec-6] o.s.s.oauth2.client.OAuth2RestTemplate   : #### RETRIEVED EXPIRED ACCESS TOKEN FROM OAuth2ClientContext: a457ac1d-c486-43f2-9198-542ea0b42352 REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.553 DEBUG 9300 --- [nio-8765-exec-3] o.s.s.oauth2.client.OAuth2RestTemplate   : #### RETRIEVED EXPIRED ACCESS TOKEN FROM OAuth2ClientContext: a457ac1d-c486-43f2-9198-542ea0b42352 REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.553 DEBUG 9300 --- [nio-8765-exec-4] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.553 DEBUG 9300 --- [nio-8765-exec-6] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.553 DEBUG 9300 --- [nio-8765-exec-3] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.557 DEBUG 9300 --- [nio-8765-exec-4] g.c.AuthorizationCodeAccessTokenProvider : Retrieving token from http://localhost:8088/oauth/token
2016-09-20 11:14:36.557 DEBUG 9300 --- [nio-8765-exec-6] g.c.AuthorizationCodeAccessTokenProvider : Retrieving token from http://localhost:8088/oauth/token
2016-09-20 11:14:36.557 DEBUG 9300 --- [nio-8765-exec-4] g.c.AuthorizationCodeAccessTokenProvider : Encoding and sending form: {grant_type=[refresh_token], refresh_token=[408dd3af-5c85-4e52-9631-009d637fc0cc]}
2016-09-20 11:14:36.557 DEBUG 9300 --- [nio-8765-exec-6] g.c.AuthorizationCodeAccessTokenProvider : Encoding and sending form: {grant_type=[refresh_token], refresh_token=[408dd3af-5c85-4e52-9631-009d637fc0cc]}
2016-09-20 11:14:36.558 DEBUG 9300 --- [nio-8765-exec-3] g.c.AuthorizationCodeAccessTokenProvider : Retrieving token from http://localhost:8088/oauth/token
2016-09-20 11:14:36.558 DEBUG 9300 --- [nio-8765-exec-3] g.c.AuthorizationCodeAccessTokenProvider : Encoding and sending form: {grant_type=[refresh_token], refresh_token=[408dd3af-5c85-4e52-9631-009d637fc0cc]}
2016-09-20 11:14:36.559 DEBUG 9300 --- [nio-8765-exec-7] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.559 DEBUG 9300 --- [nio-8765-exec-7] o.s.s.oauth2.client.OAuth2RestTemplate   : #### RETRIEVED EXPIRED ACCESS TOKEN FROM OAuth2ClientContext: a457ac1d-c486-43f2-9198-542ea0b42352 REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.560 DEBUG 9300 --- [nio-8765-exec-7] o.s.s.oauth2.client.OAuth2RestTemplate   : ############################################################################################################################################################
2016-09-20 11:14:36.563 DEBUG 9300 --- [nio-8765-exec-7] g.c.AuthorizationCodeAccessTokenProvider : Retrieving token from http://localhost:8088/oauth/token
2016-09-20 11:14:36.563 DEBUG 9300 --- [nio-8765-exec-7] g.c.AuthorizationCodeAccessTokenProvider : Encoding and sending form: {grant_type=[refresh_token], refresh_token=[408dd3af-5c85-4e52-9631-009d637fc0cc]}
2016-09-20 11:14:36.577 DEBUG 9300 --- [nio-8765-exec-6] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
2016-09-20 11:14:36.577 DEBUG 9300 --- [nio-8765-exec-6] o.s.s.oauth2.client.OAuth2RestTemplate   : #### TOKEN REFRESHED!!! UPDATED CONTEXT WITH NEW TOKEN, WRITTEN IN OAuth2ClientContext: **31cbd949-7eb7-438d-bc9e-a313ddb902e9** REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.577 DEBUG 9300 --- [nio-8765-exec-6] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
2016-09-20 11:14:36.581 DEBUG 9300 --- [nio-8765-exec-7] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
2016-09-20 11:14:36.581 DEBUG 9300 --- [nio-8765-exec-7] o.s.s.oauth2.client.OAuth2RestTemplate   : #### TOKEN REFRESHED!!! UPDATED CONTEXT WITH NEW TOKEN, WRITTEN IN OAuth2ClientContext: **c2304922-499c-4ff5-ab75-6bd3d5cc8f09** REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.581 DEBUG 9300 --- [nio-8765-exec-7] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
2016-09-20 11:14:36.587 DEBUG 9300 --- [nio-8765-exec-4] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
2016-09-20 11:14:36.587 DEBUG 9300 --- [nio-8765-exec-4] o.s.s.oauth2.client.OAuth2RestTemplate   : #### TOKEN REFRESHED!!! UPDATED CONTEXT WITH NEW TOKEN, WRITTEN IN OAuth2ClientContext: **7356c3ef-a660-4ccb-a542-4c7dd16e3aa5** REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.587 DEBUG 9300 --- [nio-8765-exec-4] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
2016-09-20 11:14:36.593 DEBUG 9300 --- [nio-8765-exec-5] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
2016-09-20 11:14:36.593 DEBUG 9300 --- [nio-8765-exec-5] o.s.s.oauth2.client.OAuth2RestTemplate   : #### TOKEN REFRESHED!!! UPDATED CONTEXT WITH NEW TOKEN, WRITTEN IN OAuth2ClientContext: **b30ffc9f-6c98-4137-8c01-06b3bebc1ae9** REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.593 DEBUG 9300 --- [nio-8765-exec-5] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
2016-09-20 11:14:36.598 DEBUG 9300 --- [nio-8765-exec-3] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
2016-09-20 11:14:36.598 DEBUG 9300 --- [nio-8765-exec-3] o.s.s.oauth2.client.OAuth2RestTemplate   : #### TOKEN REFRESHED!!! UPDATED CONTEXT WITH NEW TOKEN, WRITTEN IN OAuth2ClientContext: **4f6d0500-6873-475b-b94a-e0294220fec3** REFRESH TOKEN: 408dd3af-5c85-4e52-9631-009d637fc0cc
2016-09-20 11:14:36.598 DEBUG 9300 --- [nio-8765-exec-3] o.s.s.oauth2.client.OAuth2RestTemplate   : #################################################################################################################################################################################
'''


