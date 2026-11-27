package cn.edu.lab3.delivery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
class WebConfig implements WebMvcConfigurer {
  private final Db db;

  WebConfig(Db db) {
    this.db = db;
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  BCryptPasswordEncoder passwords() {
    return new BCryptPasswordEncoder();
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new AccessGuard(db)).addPathPatterns("/api/**");
  }

  private record AccessGuard(Db db) implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
      res.setHeader("Cache-Control", "no-store");
      if (!req.getMethod().equals("GET") && !"delivery".equals(req.getHeader("X-Requested-With"))) {
        throw new Problem(403, "缺少同源请求标识");
      }
      if (req.getRequestURI().matches("/api/auth/(login|register)")) {
        return true;
      }
      var session = req.getSession(false);
      if (session == null || !(session.getAttribute("userId") instanceof Long id)) {
        throw new Problem(401, "请先登录");
      }
      var user = db.one("SELECT id,account,role,enabled FROM users WHERE id=?", id);
      var actor = Actor.from(user);
      req.setAttribute("actor", actor);
      if (!Db.flag(user, "enabled")) {
        session.invalidate();
        throw new Problem(403, "账号已停用");
      }
      return true;
    }
  }
}
