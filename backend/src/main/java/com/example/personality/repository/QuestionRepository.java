package com.example.personality.repository;

import com.example.personality.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 题库的数据访问接口。
 *
 * <p><b>关于 Spring Data JPA 最大的一处"魔法"，这里必须解释清楚。</b>
 *
 * <p>这个接口<b>没有任何实现类</b>。你启动应用时，Spring Data 会扫描到它，
 * 用动态代理在运行时生成一个实现，并注册成 Bean 供你注入。
 *
 * <p>你在 {@code JdbcCrud.java} 里是这么写的：
 * <pre>
 *   String sql = "SELECT * FROM questions ORDER BY sort_order";
 *   PreparedStatement ps = conn.prepareStatement(sql);
 *   ResultSet rs = ps.executeQuery();
 *   while (rs.next()) { 手动映射每一列到对象 }
 * </pre>
 *
 * <p>现在这些全都不用手写了：
 * <ul>
 *   <li>继承 {@link JpaRepository} 直接白送你 {@code save / findById / findAll /
 *       deleteById / count} 等 18 个方法，连 SQL 都不用写</li>
 *   <li>{@link #findAllByOrderBySortOrderAsc()} 这种"查询方法"，Spring 会
 *       <b>解析方法名的英文语法</b>生成对应 SQL。规则是：
 *       {@code findAllBy + 字段名 + OrderBy + 字段名 + Asc/Desc}</li>
 * </ul>
 *
 * <p>上面这个方法实际执行的 SQL 是：
 * <pre>SELECT * FROM questions ORDER BY sort_order ASC</pre>
 *
 * <p><b>⚠️ 但方法名写错了不会编译报错，只在启动时才炸。</b>
 * 比如手滑写成 {@code findAllByOrderBySortOrdrAsc}（少个 e），
 * 编译完全通过，但应用启动时抛
 * {@code PropertyReferenceException: No property 'sortOrdr' found}。
 * 这是新手最常见的启动失败原因——看到这个异常，先去检查方法名拼写。
 */
public interface QuestionRepository extends JpaRepository<Question, Long> {

    /** 按展示顺序取出全部题目。≡ SELECT * FROM questions ORDER BY sort_order ASC */
    List<Question> findAllByOrderBySortOrderAsc();
}
