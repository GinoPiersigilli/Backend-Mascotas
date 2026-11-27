package utnfc.isi.back.tpi.donaciones.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Configura ShedLock sobre PostgreSQL (tabla donaciones.shedlock, migracion V3) para evitar
 * ejecuciones duplicadas de la reconciliacion de donaciones en multiples replicas.
 */
@Configuration
@ConditionalOnProperty(name = "donaciones.tareas.habilitadas", havingValue = "true", matchIfMissing = true)
@EnableSchedulerLock(defaultLockAtMostFor = "PT4M")
public class ShedLockConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("donaciones.shedlock")
                .usingDbTime()
                .build());
    }
}
