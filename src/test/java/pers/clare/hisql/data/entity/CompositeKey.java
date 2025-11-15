package pers.clare.hisql.data.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.core.annotation.Order;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.io.Serializable;

@Getter
@Setter
@Accessors(chain = true)
public class CompositeKey implements Serializable {

    @Order(1)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Order(2)
    @Id
    private String account;

    public CompositeKey() {
    }

    public CompositeKey(Long id, String account) {
        this.id = id;
        this.account = account;
    }
}
