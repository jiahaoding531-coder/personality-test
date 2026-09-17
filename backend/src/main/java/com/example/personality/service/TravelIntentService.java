package com.example.personality.service;

import com.example.personality.ai.AiCredentials;
import com.example.personality.ai.TextIntentGenerator;
import com.example.personality.ai.TextIntentResult;
import com.example.personality.ai.TravelIntentOperation;
import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.dto.InterpretResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把用户的一句大白话翻译成结构化条件。
 *
 * <h2>这个类为什么这么薄</h2>
 *
 * <p>别的 AI 服务（{@code AiReportService}、{@code TravelReasonService}）都很长，
 * 因为它们要「读库 → 调模型 → 写库」。这个不用：**它既不读也不写**，
 * 拿一句话进去、拿结构出来，一次调用就完了。
 *
 * <p>那为什么还留着这一层？两个理由：
 * <ol>
 *   <li><b>一致性</b>：这个项目里所有 AI 调用都经过一个 service。
 *       少一处就要多解释一次"为什么这个不一样"</li>
 *   <li><b>以后会长</b>：真要给这个功能加限流、加解析结果的缓存、
 *       或者加"把用户常说的话沉淀成新状态"，落点都在这里</li>
 * </ol>
 *
 * <p>顺带说一句**它不需要 {@code @Transactional}**——
 * 不是因为"反正没写库"这种侥幸，而是因为它压根不碰数据库。
 * 别的服务要小心事务，是因为它们碰了；这里连碰都没碰。
 */
@Service
public class TravelIntentService {

    private final TextIntentGenerator intentGenerator;

    public TravelIntentService(TextIntentGenerator intentGenerator) {
        this.intentGenerator = intentGenerator;
    }

    /**
     * @param credentials 这次用谁的 key。由控制器在事务外解析好
     * @throws com.example.personality.exception.AiServiceException 上游故障（502）
     * @throws com.example.personality.exception.InvalidAiCredentialsException
     *         凭据是用户给的且被上游拒绝（400）
     */
    public InterpretResponse interpret(AiCredentials credentials, String text) {
        TextIntentResult result = intentGenerator.interpret(credentials, text);

        List<InterpretResponse.StateLabel> states = new ArrayList<>(result.states().size());
        for (TravelState state : result.states()) {
            states.add(new InterpretResponse.StateLabel(state.name(), state.label()));
        }

        // 用维度名当键传给前端（CROWD_TOLERANCE 而不是"热闹"）：
        // 前端要把它原样回传在 recommendations 请求的 biases 里，
        // 用中文当键的话还得再翻译回去，多一道没有意义的往返。
        // 中文名前端展示时现查——那边的 DIMENSION_LABELS 已经有了。
        Map<String, Double> biases = new LinkedHashMap<>();
        for (Map.Entry<TravelDimension, Double> entry : result.biases().entrySet()) {
            biases.put(entry.getKey().name(), entry.getValue());
        }

        return new InterpretResponse(
                states,
                biases,
                result.remainingMinutes(),
                result.maxDistanceKm(),
                result.maxTicketPrice(),
                result.unrecognized(),
                result.summary(),
                result.hasAnythingUsable(),
                toOperations(result.operations()));
    }

    private List<InterpretResponse.Operation> toOperations(List<TravelIntentOperation> operations) {
        List<InterpretResponse.Operation> converted = new ArrayList<>(operations.size());
        for (TravelIntentOperation operation : operations) {
            Object value = operation.state() == null ? operation.numberValue() : operation.state().name();
            Object values = null;
            if (!operation.states().isEmpty()) {
                values = operation.states().stream().map(Enum::name).toList();
            } else if (!operation.biases().isEmpty()) {
                Map<String, Double> namedBiases = new LinkedHashMap<>();
                operation.biases().forEach((key, bias) -> namedBiases.put(key.name(), bias));
                values = namedBiases;
            }
            converted.add(new InterpretResponse.Operation(
                    operation.op().name(),
                    value,
                    values,
                    constraintKey(operation.constraintKey())));
        }
        return converted;
    }

    private String constraintKey(TravelIntentOperation.ConstraintKey key) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case DURATION_MINUTES -> "durationMinutes";
            case MAX_DISTANCE_METERS -> "maxDistanceMeters";
            case BUDGET_MAX -> "budgetMax";
        };
    }
}
