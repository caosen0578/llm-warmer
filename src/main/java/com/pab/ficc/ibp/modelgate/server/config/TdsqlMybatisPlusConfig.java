package com.pab.ficc.ibp.modelgate.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.pab.ficc.cus.mybatis.plugins.QueryInterceptor;
import com.pab.middleware.venuscommon.filter.MybatisSQLInterceptor;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.plugin.Interceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * 内网 TDSQL + MyBatis Plus 配置。
 *
 * <p>使用内网 VenusDataSourceBean 替代 HikariCP，连接参数由 Apollo
 * {@code tdsql_datasource_config} 命名空间下发，本地 application.yml 无需配置。
 *
 * <p>拦截器顺序（按 plugins 数组顺序执行）：
 * <ol>
 *   <li>QueryInterceptor：内网自定义查询拦截（权限、租户等）</li>
 *   <li>MybatisPlusInterceptor：分页插件（PaginationInnerInterceptor）</li>
 *   <li>MybatisSQLInterceptor：内网 SQL 审计拦截</li>
 * </ol>
 */
@Configuration
@MapperScan(
        basePackages = "com.pab.ficc.ibp.modelgate.server.mapper",
        sqlSessionFactoryRef = "tdsqlSessionFactory"
)
public class TdsqlMybatisPlusConfig {

    @Bean
    public QueryInterceptor createQueryInterceptor() {
        return new QueryInterceptor();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页拦截器，指定 MySQL 方言
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public MybatisSQLInterceptor createMybatisSQLInterceptor() {
        return new MybatisSQLInterceptor();
    }

    @Bean(name = "tdsqlSessionFactory")
    public MybatisSqlSessionFactoryBean tdsqlSessionFactory(
            @Qualifier("tdsqlDataSource") DataSource dataSource,
            QueryInterceptor queryInterceptor,
            MybatisPlusInterceptor mybatisPlusInterceptor,
            MybatisSQLInterceptor mybatisSQLInterceptor) throws Exception {

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        // 下划线转驼峰：DB 字段 total_requests → Java 字段 totalRequests
        configuration.setMapUnderscoreToCamelCase(true);
        // 关闭 SQL 日志（高频预热调用下避免日志噪声，调试时可临时改为 StdOutImpl）
        configuration.setLogImpl(NoLoggingImpl.class);
        factory.setConfiguration(configuration);

        factory.setPlugins(new Interceptor[]{
                queryInterceptor,
                mybatisPlusInterceptor,
                mybatisSQLInterceptor
        });

        // 扫描 classpath 下所有 mapper XML（当前项目纯注解，此处为预留扩展）
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/**/*.xml")
        );

        return factory;
    }
}
