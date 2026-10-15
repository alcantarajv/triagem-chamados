package com.joaoalcantara.triagem.chamado;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 *
 * <p>As transicoes passam todas por {@link #mudarPara}, que consulta o grafo de
 * {@link StatusChamado}. Qualquer caminho que chegue a entidade — o worker hoje,
 * o job de nova tentativa e a triagem manual depois — obedece a mesma regra.</p>
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

    @Enumerated(EnumType.STRING)
    @Column(name = "categoria", length = 20)
    private Categoria categoria;

    @Enumerated(EnumType.STRING)
    @Column(name = "prioridade", length = 10)
    private Prioridade prioridade;

    @Column(name = "resumo", length = Classificacao.TAMANHO_MAXIMO_DO_RESUMO)
    private String resumo;

    @Column(name = "provedor", length = 30)
    private String provedor;

    @Column(name = "triado_em")
    private Instant triadoEm;

    /**
     * Ate quando o chamado pertence ao worker que o reservou.
     *
     * <p>Enquanto a reserva vale, nenhum outro worker o pega. Se o worker morrer
     * no meio da chamada ao provedor, a reserva vence sozinha e o chamado volta
     * para a fila — sem job de limpeza e sem chamado preso para sempre.</p>
     */
    @Column(name = "reservado_ate")
    private Instant reservadoAte;

    /** Rodadas de triagem automatica que ja falharam para este chamado. */
    @Column(name = "falhas_de_triagem", nullable = false)
    private int falhasDeTriagem;

    /**
     * Quando o revisor de pendentes tenta de novo. Nula quando nao ha tentativa
     * agendada: chamado que nao esta pendente, ou pendente que ja esgotou as
     * tentativas automaticas e espera so uma pessoa.
     */
    @Column(name = "proxima_tentativa_em")
    private Instant proximaTentativaEm;

    /** A classificacao foi reaproveitada de outro chamado com o mesmo texto, sem chamar provedor. */
    @Column(name = "veio_do_cache", nullable = false)
    private boolean veioDoCache;

    /** Provedores que falharam antes do que classificou, separados por virgula. */
    @Column(name = "provedores_que_falharam", length = 100)
    private String provedoresQueFalharam;

    @Column(name = "duracao_da_triagem_ms")
    private Integer duracaoDaTriagemMs;

    protected Chamado() {
        // exigido pelo JPA
    }

    public Chamado(String texto, Instant criadoEm) {
        this.texto = Objects.requireNonNull(texto, "texto");
        this.criadoEm = Objects.requireNonNull(criadoEm, "criadoEm");
        this.status = StatusChamado.RECEBIDO;
    }

    public void reservarAte(Instant limite) {
        this.reservadoAte = Objects.requireNonNull(limite, "limite");
    }

    /** Aplica o resultado da triagem automatica e encerra a reserva. */
    public void registrarTriagem(ResultadoDaTriagem resultado, Instant agora) {
        mudarPara(StatusChamado.TRIADO);
        Classificacao classificacao = resultado.classificacao();
        this.categoria = Objects.requireNonNull(classificacao.categoria(), "categoria");
        this.prioridade = Objects.requireNonNull(classificacao.prioridade(), "prioridade");
        this.resumo = Objects.requireNonNull(classificacao.resumo(), "resumo");
        this.provedor = resultado.provedor();
        this.veioDoCache = resultado.doCache();
        this.provedoresQueFalharam = resultado.provedoresQueFalharam().isEmpty() ? null
                : String.join(",", resultado.provedoresQueFalharam());
        this.duracaoDaTriagemMs = Math.toIntExact(resultado.duracao().toMillis());
        this.triadoEm = Objects.requireNonNull(agora, "agora");
        this.reservadoAte = null;
        this.proximaTentativaEm = null;
    }

    /**
     * Uma rodada de triagem automatica falhou: o chamado fica pendente e, se
     * ainda houver espera na lista, ganha a proxima tentativa agendada.
     *
     * <p>A espera cresce a cada falha. O motivo mais comum de uma rodada inteira
     * falhar — todos os provedores fora, ou a cota do dia esgotada — nao passa em
     * segundos; tentar de novo logo so gastaria a cota que sobrou. Esgotada a
     * lista, a triagem automatica desiste de vez e o chamado espera uma pessoa,
     * em vez de ficar sendo revisitado para sempre.</p>
     *
     * @param esperas espera antes de cada nova tentativa: a primeira depois da
     *                primeira falha, a segunda depois da segunda, e assim por diante
     */
    public void registrarFalhaDaTriagem(Instant agora, List<Duration> esperas) {
        if (status == StatusChamado.RECEBIDO) {
            mudarPara(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        } else if (status != StatusChamado.PENDENTE_TRIAGEM_MANUAL) {
            throw new TransicaoInvalidaException(id, status, StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        }
        this.falhasDeTriagem++;
        this.proximaTentativaEm = falhasDeTriagem <= esperas.size()
                ? agora.plus(esperas.get(falhasDeTriagem - 1))
                : null;
        this.reservadoAte = null;
    }

    private void mudarPara(StatusChamado destino) {
        if (!status.permiteIrPara(destino)) {
            throw new TransicaoInvalidaException(id, status, destino);
        }
        this.status = destino;
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

    public Categoria getCategoria() {
        return categoria;
    }

    public Prioridade getPrioridade() {
        return prioridade;
    }

    public String getResumo() {
        return resumo;
    }

    public String getProvedor() {
        return provedor;
    }

    public Instant getTriadoEm() {
        return triadoEm;
    }

    public Instant getReservadoAte() {
        return reservadoAte;
    }

    public int getFalhasDeTriagem() {
        return falhasDeTriagem;
    }

    public Instant getProximaTentativaEm() {
        return proximaTentativaEm;
    }

    public boolean isVeioDoCache() {
        return veioDoCache;
    }

    public List<String> getProvedoresQueFalharam() {
        return provedoresQueFalharam == null ? List.of() : List.of(provedoresQueFalharam.split(","));
    }

    public Integer getDuracaoDaTriagemMs() {
        return duracaoDaTriagemMs;
    }
}
