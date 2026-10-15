package com.joaoalcantara.triagem.chamado;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Chamado de suporte em texto livre.
 *
 * <p>Nasce {@link StatusChamado#RECEBIDO} e so isso: gravar o chamado nao
 * depende de IA nenhuma. E o que garante que um provedor fora do ar nunca
 * impede o cliente de abrir um chamado.</p>
 */
@Entity
@Table(name = "chamados")
public class Chamado {

    /** Tamanho maximo do texto, o mesmo da restricao no banco. */
    public static final int TAMANHO_MAXIMO_DO_TEXTO = 4000;

    /**
     * UUID versao 7, gerado pelo Hibernate no {@code persist}.
     *
     * <p>Versao 7 em vez da 4 (totalmente aleatoria): os primeiros bits sao o
     * instante de criacao, entao os ids novos entram no fim do indice da chave
     * primaria. Continua impossivel de adivinhar — o restante e aleatorio —, que
     * e o motivo de nao ser um numero sequencial.</p>
     */
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "texto", nullable = false, length = TAMANHO_MAXIMO_DO_TEXTO)
    private String texto;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StatusChamado status;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    protected Chamado() {
        // exigido pelo JPA
    }

    public Chamado(String texto, Instant criadoEm) {
        this.texto = Objects.requireNonNull(texto, "texto");
        this.criadoEm = Objects.requireNonNull(criadoEm, "criadoEm");
        this.status = StatusChamado.RECEBIDO;
    }

    public UUID getId() {
        return id;
    }

    public String getTexto() {
        return texto;
    }

    public StatusChamado getStatus() {
        return status;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
