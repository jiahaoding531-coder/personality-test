package com.example.personality.entity;

import com.example.personality.domain.PlaceCandidate;
import com.example.personality.domain.PlaceTraits;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalTime;

/**
 * 旅游地点，对应 places 表。
 *
 * <p>V0 用模拟数据（见 V7 迁移）。将来换成高德等真实数据源时，
 * 只要这张表的字段还能装下，业务代码一行不用改。
 */
@Entity
@Table(name = "places")
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "city", nullable = false, length = 50)
    private String city;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    // ---- 7 个属性维度，0~100 ----
    @Column(name = "nature", nullable = false)
    private int nature;
    @Column(name = "culture", nullable = false)
    private int culture;
    @Column(name = "food", nullable = false)
    private int food;
    @Column(name = "photography", nullable = false)
    private int photography;
    @Column(name = "hidden_gems", nullable = false)
    private int hiddenGems;
    @Column(name = "lively", nullable = false)
    private int lively;
    @Column(name = "walking", nullable = false)
    private int walking;

    @Column(name = "ticket_price", nullable = false)
    private int ticketPrice;

    @Column(name = "suggested_minutes", nullable = false)
    private int suggestedMinutes;

    /** 营业开始时间。null 表示全天开放（公园、街区）。 */
    @Column(name = "open_from")
    private LocalTime openFrom;

    @Column(name = "open_to")
    private LocalTime openTo;

    @Column(name = "quality", nullable = false)
    private int quality;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Place() {
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * 转成推荐引擎认识的对象。
     *
     * <p>这是实体层和算法层之间唯一的接触点。放在实体上而不是塞进
     * 引擎里，是为了让引擎完全不依赖 JPA——它连 {@code Place} 这个类
     * 都不需要 import。
     */
    public PlaceCandidate toCandidate() {
        return new PlaceCandidate(
                id, name, category, latitude, longitude,
                new PlaceTraits(nature, culture, food, photography, hiddenGems, lively, walking),
                quality, ticketPrice, suggestedMinutes,
                openFrom, openTo, description);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getCity() {
        return city;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public int getNature() {
        return nature;
    }

    public int getCulture() {
        return culture;
    }

    public int getFood() {
        return food;
    }

    public int getPhotography() {
        return photography;
    }

    public int getHiddenGems() {
        return hiddenGems;
    }

    public int getLively() {
        return lively;
    }

    public int getWalking() {
        return walking;
    }

    public int getTicketPrice() {
        return ticketPrice;
    }

    public int getSuggestedMinutes() {
        return suggestedMinutes;
    }

    public LocalTime getOpenFrom() {
        return openFrom;
    }

    public LocalTime getOpenTo() {
        return openTo;
    }

    public int getQuality() {
        return quality;
    }

    public String getDescription() {
        return description;
    }
}
