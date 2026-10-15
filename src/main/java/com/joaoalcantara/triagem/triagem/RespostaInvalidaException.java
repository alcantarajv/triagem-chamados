package com.joaoalcantara.triagem.triagem;

import java.util.List;

/** O provedor respondeu, mas nem a resposta nem a correcao passaram na validacao. */
public class RespostaInvalidaException extends RuntimeException {

    private final String provedor;
    private final List<String> erros;

    public RespostaInvalidaException(String provedor, List<String> erros) {
        super("Resposta de %s recusada duas vezes: %s".formatted(provedor, String.join("; ", erros)));
        this.provedor = provedor;
        this.erros = List.copyOf(erros);
    }

    public String provedor() {
        return provedor;
    }

    public List<String> erros() {
        return erros;
    }
}
