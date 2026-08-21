package org.zhzssp.memorandum.feature.codex.verify;

import org.zhzssp.memorandum.feature.codex.entity.KbCheckpoint;

/**
 * 一条落地检验被判定的事实。
 *
 * <h3>为什么用事件而不是让 CheckpointService 直接调 GapService</h3>
 * <p>与 P1 的 {@code RepoIndexedEvent} 同一立场：验证闭环是基础能力，
 * 缺口台账是它之上的可选特性（{@code codex.gap.enabled} 可独立关闭）。
 * 事件解耦让「关掉缺口闭环时验证行为完全不变」成为结构性保证，
 * 而不是靠一个散落在业务代码里的 if。</p>
 *
 * <h3>这里承载的是本设计里质量最高的两个缺口信号</h3>
 * <ul>
 *   <li>{@code FAILED} —— 动手做不出来。不是「我感觉没掌握」，是检验真的没过。</li>
 *   <li>{@code predictionCorrect=false} —— <strong>结果对但因果理解错</strong>。
 *       这是所有假通过里最危险的一类，而且市面上没有任何软件在采集它。</li>
 * </ul>
 *
 * @param userId          用户
 * @param repoId          仓库
 * @param checkpointId    检验条目 id
 * @param code            条目编号，如 {@code L2-MLIR-04}
 * @param title           条目标题
 * @param status          判定后的状态
 * @param predictionCorrect 预测是否正确；null 表示尚未判定
 * @param divergence      「我原以为…实际…」
 */
public record CheckpointJudgedEvent(Long userId,
                                    Long repoId,
                                    Long checkpointId,
                                    String code,
                                    String title,
                                    KbCheckpoint.Status status,
                                    Boolean predictionCorrect,
                                    String divergence) {

    /** 执行失败：做不出来。 */
    public boolean failed() {
        return status == KbCheckpoint.Status.FAILED;
    }

    /** 通过但预测错——最高价值信号。 */
    public boolean mispredicted() {
        return Boolean.FALSE.equals(predictionCorrect);
    }
}
