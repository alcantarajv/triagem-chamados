package com.joaoalcantara.triagem.provedor;

/** O provedor nao devolveu uma classificacao utilizavel, por qualquer motivo. */
public class FalhaNoProvedorException extends RuntimeException {

    private final String provedor;

    public FalhaNoProvedorException(String provedor, Throwable causa) {
        super("Falha no provedor %s: %s".formatted(provedor, causa.getMessage()), causa);
        this.provedor = provedor;
    }

    public String provedor() {
        return provedor;
    }
}
