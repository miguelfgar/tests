package testcase.gateway;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.session.data.redis.RedisOperationsSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.data.redis.RedisFlushMode;

//About setting session timeout with RedisHttpSession
//https://github.com/spring-projects/spring-session/issues/110

@SuppressWarnings("rawtypes")
@Configuration
@EnableRedisHttpSession(redisFlushMode = RedisFlushMode.IMMEDIATE)
// https://github.com/spring-projects/spring-session/issues/551 @EnableRedisHttpSession(redisFlushMode = RedisFlushMode.IMMEDIATE)
public class RedisHttpSessionTimeoutConfiguration implements ApplicationListener {

	@Value("${spring.session.timeout}")
    private Integer maxInactiveIntervalInSeconds;
	
	@Autowired
	private RedisOperationsSessionRepository redisOperation;
	
	@Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ContextRefreshedEvent) {
        	redisOperation.setDefaultMaxInactiveInterval(maxInactiveIntervalInSeconds.intValue());
        	// Just to try as the annotation does not seem to provide a different behabiour..
        	redisOperation.setRedisFlushMode(RedisFlushMode.IMMEDIATE);
        }
    }

}
