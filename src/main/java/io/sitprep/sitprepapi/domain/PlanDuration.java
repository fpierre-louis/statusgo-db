package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Embeddable;
import lombok.Getter;

@Getter
@Embeddable
public class PlanDuration {

    private int quantity;
    private String unit;

    // Getters and Setters

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}
