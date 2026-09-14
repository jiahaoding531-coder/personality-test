package com.example.personality.service;

import com.example.personality.dto.QuestionResponse;
import com.example.personality.dto.QuestionsResponse;
import com.example.personality.dto.ScaleOption;
import com.example.personality.entity.Question;
import com.example.personality.repository.QuestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 题库读取。
 *
 * <p>这是最简单的 Service，适合用来理解分层：Controller 只负责 HTTP 的进出，
 * Service 负责业务，Repository 负责数据库。Controller 里看不到任何 SQL，
 * Service 里也看不到任何 HTTP 相关的东西。
 */
@Service
public class QuestionService {

    /**
     * 李克特量表的 5 个选项。
     *
     * <p>用 {@code List.of(...)} 创建不可变列表。写成 {@code static final} 常量，
     * 是因为所有请求返回的都是同一份内容，没必要每次请求都重新构造。
     *
     * <p>量表文案放后端而不是前端硬编码，见 ScaleOption 的注释。
     */
    private static final List<ScaleOption> SCALE_OPTIONS = List.of(
            new ScaleOption(1, "非常不同意"),
            new ScaleOption(2, "比较不同意"),
            new ScaleOption(3, "说不好"),
            new ScaleOption(4, "比较同意"),
            new ScaleOption(5, "非常同意")
    );

    private final QuestionRepository questionRepository;

    /**
     * <b>构造器注入</b>——本项目统一用这种写法，不用 {@code @Autowired} 注解字段。
     *
     * <p>好处有三个：
     * <ol>
     *   <li>字段可以声明成 {@code final}，对象一旦创建依赖就不可变，线程安全</li>
     *   <li>依赖关系一眼可见——构造器参数有 5 个，说明这个类职责太重了</li>
     *   <li>写单元测试时可以直接 {@code new QuestionService(mockRepo)}，
     *       不需要启动 Spring 容器</li>
     * </ol>
     *
     * <p>Spring 4.3 之后，如果一个类只有一个构造器，{@code @Autowired} 可以省略，
     * Spring 会自动用它来注入。
     */
    public QuestionService(QuestionRepository questionRepository) {
        this.questionRepository = questionRepository;
    }

    /** 取出全部题目 + 量表选项。 */
    @Transactional(readOnly = true)
    public QuestionsResponse getQuestions() {
        List<Question> entities = questionRepository.findAllByOrderBySortOrderAsc();

        List<QuestionResponse> questions = new ArrayList<>(entities.size());
        for (Question question : entities) {
            questions.add(QuestionResponse.from(question));
        }

        return new QuestionsResponse(SCALE_OPTIONS, questions);
    }

    /**
     * 取出题库总题数，用于提交时校验"是不是每道题都答了"。
     *
     * <p>注意这里的思路和上面相反：不需要知道题目内容，只要一个数量。
     * Repository 继承来的 {@code count()} 会生成 {@code SELECT count(*) FROM questions}，
     * 比把所有题目查出来再 {@code .size()} 高效得多。
     */
    @Transactional(readOnly = true)
    public long countQuestions() {
        return questionRepository.count();
    }
}
