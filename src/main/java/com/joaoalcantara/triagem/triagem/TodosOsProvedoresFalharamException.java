package com.joaoalcantara.triagem.triagem;

import java.util.List;

/** Nenhum provedor da cadeia produziu uma classificacao aceitavel. */
public class TodosOsProvedoresFalharamException extends RuntimeException {

    private final List<String> motivos;

    public TodosOsProvedoresFalharamException(List<String> motivos) {
        super(motivos.isEmpty() ? "nenhum provedor de IA configurado" : String.join(" | ", motivos));
        this.motivos = List.copyOf(motivos);
    }

    /** Um motivo por provedor consultado, na ordem da cadeia. */
    public List<String> motivos() {
        return motivos;
    }
}
