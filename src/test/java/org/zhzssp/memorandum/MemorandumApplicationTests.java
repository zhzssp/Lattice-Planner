package org.zhzssp.memorandum;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 生产装配的冒烟测试：整个 Spring 上下文能否起来。
 *
 * <p>看起来只有一行 {@code System.out.println} 的空测试，实际覆盖面是全项目最宽的一条——
 * 它要求每个 bean 都能被构造、每处依赖都能被解析、每个 {@code @ConfigurationProperties}
 * 都能完成绑定。<b>循环依赖、构造器参数写错、配置项名字拼错，都在这里第一时间暴露</b>。
 *
 * <h3>为什么要挂 test profile</h3>
 * 生产 datasource 指向 {@code localhost:3306} 的真实 MySQL。本机没起 MySQL 时
 * Hibernate 读不到 JDBC metadata，报的是 <b>"Unable to determine Dialect without JDBC
 * metadata"</b>——这句话极具误导性，看着像少配了 dialect，实际是连不上库。
 * 这条测试因此长期挂红，久而久之就被当成"已知失败"忽略掉了，
 * <b>而它一旦被忽略，上面那整片覆盖面就等于没有</b>。
 *
 * <p>{@code application-test.properties} 只把 datasource 换成 H2，其余沿用生产默认值。
 * 刻意不复用评测用的 {@code agenteval} profile——但理由<b>不是</b>"那样会少创建 bean"：
 * 项目里没有任何 {@code @ConditionalOnProperty}，两份配置的 bean 集合完全相同。
 * 真实差别在<b>启动期的运行分支</b>：{@code ApplicationReadyEvent} 在
 * {@code @SpringBootTest} 里确实会触发，于是 {@code McpClientManager} 真的去连
 * loopback、{@code ToolRegistry} 真的注册 63 个工具、{@code CodexSearchService} 真的注册
 * 加载器；而 agenteval 把这些开关全关了，分支就都不执行。
 *
 * <h3>它仍然不能替代什么</h3>
 * H2 只能证明<b>装配</b>没问题，证明不了 MySQL 原生 SQL（{@code MATCH...AGAINST}
 * 全文索引等）能跑。那部分只能靠真实 MySQL 或 Testcontainers 覆盖。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemorandumApplicationTests {

	@Test
	void contextLoads() {
        System.out.println("Context loads successfully.");
	}

}
