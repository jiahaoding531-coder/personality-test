package com.example.personality.repository;

import com.example.personality.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    /**
     * 取某个城市的全部地点，作为推荐的候选池。
     *
     * <p>V0 只有杭州 59 个点，一次全取进内存完全没问题。
     * <b>这是刻意的简化</b>：把"选哪些地点作为候选"当成算法问题之前，
     * 先确认算法本身有效。
     *
     * <p>将来地点多了（全国几万个），策略要变成"先用数据库按距离和类别
     * 粗筛出几百个，再交给引擎精排"。到那时改的是这个方法的实现，
     * {@code RecommendationEngine} 一行都不用动——它的输入本来就是
     * 一个 List，不关心这 List 是怎么来的。
     */
    List<Place> findByCity(String city);
}
