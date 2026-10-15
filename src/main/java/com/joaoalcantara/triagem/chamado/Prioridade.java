package com.joaoalcantara.triagem.chamado;

public enum Prioridade {
    /** Duvida, sugestao, detalhe cosmetico. */
    BAIXA,
    /** Atrapalha, mas existe contorno. */
    MEDIA,
    /** Impede o cliente de trabalhar, sem contorno. */
    ALTA,
    /** Sistema inteiro parado, ou perda de dados ou de dinheiro acontecendo agora. */
    CRITICA
}
