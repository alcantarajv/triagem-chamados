package com.joaoalcantara.triagem.chamado;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corpo do {@code POST /api/chamados}. */
public record NovoChamado(
        @NotBlank(message = "nao pode ser vazio")
        @Size(max = Chamado.TAMANHO_MAXIMO_DO_TEXTO, message = "deve ter no maximo {max} caracteres")
        String texto) {
}
