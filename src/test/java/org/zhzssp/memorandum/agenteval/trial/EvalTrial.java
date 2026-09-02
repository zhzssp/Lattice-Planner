package org.zhzssp.memorandum.agenteval.trial;

import org.junit.jupiter.api.TestTemplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个参与<b>多试次可靠性度量</b>的评测用例，用于替代 {@code @Test}。
 *
 * <p>试次数由系统属性 {@code -Dagent.eval.trials=k} 决定（默认 1），
 * 由 {@link EvalTrialExtension} 在运行期展开成 k 次独立调用。
 *
 * <p><b>为什么不用 {@code @RepeatedTest}</b>：注解参数必须是编译期常量，
 * 读不到系统属性。而试次数必须可在命令行调整——
 * 日常回归跑 1 次求快，发版前跑 5 次看稳定性，不该需要改代码重新编译。
 *
 * <p>每次调用都会完整走一遍 {@code @BeforeEach} / {@code @AfterEach}，
 * 因此轨迹、会话记忆、数据库都是干净的。这一点是 {@code pass^k} 有效的前提：
 * 若试次之间共享状态，失败会彼此相关，算出来的可靠性偏乐观。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
public @interface EvalTrial {
}
