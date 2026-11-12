package com.astral.business.bim.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RvtConversionResult {

    private String conversionStatus;

    private Astral3DBimToGltf item;

    private BigDecimal process;

    private String message;

    public RvtConversionResult(String conversionStatus, Astral3DBimToGltf item, BigDecimal process) {
        this(conversionStatus, item, process, null);
    }

}
