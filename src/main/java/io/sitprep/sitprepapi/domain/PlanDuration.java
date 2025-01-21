package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Embeddable;

@Embeddable
public class PlanDuration {
    private int quantity;
    private String unit;

    // Getters and Setters
    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}