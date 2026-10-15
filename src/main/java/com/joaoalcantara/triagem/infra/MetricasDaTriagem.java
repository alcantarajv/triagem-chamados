package com.joaoalcantara.triagem.infra;

import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * As metricas de negocio deste sistema, num lugar so.
 *
 * <p>O Actuator ja entrega as metricas tecnicas — latencia HTTP, pool de
 * conexoes, memoria. Elas respondem "a aplicacao esta de pe?". As daqui
 * respondem a pergunta deste projeto: <b>como os provedores de IA estao se
 * comportando, e quanto cada um esta custando?</b> Um provedor lento, um
 * fallback que virou caminho de todo dia, um cache que nunca acerta, um
 * orcamento que acaba as 15h — nada disso aparece no health check.</p>
 *
 * <p>Todas as etiquetas tem um conjunto pequeno e fechado de valores: nome do
 * provedor, resultado, motivo. Etiqueta com id de chamado ou texto multiplicaria
 * as series no Prometheus a cada chamado — e o texto nem pode sair daqui.</p>
 *
 * <p>Centralizar evita o problema classico de metrica: nome inventado em cada
 * ponto do codigo, e ninguem consegue montar um painel porque
 * {@code triagem.cache} e {@code cache_triagem} convivem.</p>
 */
@Component
public class MetricasDaTriagem {

    private final MeterRegistry registro;
    private final Counter chamadosRegistrados;

    public MetricasDaTriagem(MeterRegistry registro) {
        this.registro = registro;
        this.chamadosRegistrados = Counter.builder("triagem.chamados.registrados")
                .description("Chamados aceitos pela API")
                .register(registro);
    }

    /**
     * Cada requisicao que de fato saiu para um provedor: quanto demorou e como
     * terminou ({@code sucesso}, {@code transitoria} ou {@code definitiva}).
     */
    public void requisicaoAoProvedor(String provedor, String resultado, Duration duracao) {
        Timer.builder("triagem.provedor.requisicoes")
                .description("Requisicoes aos provedores de IA, por resultado")
                .tag("provedor", provedor)
                .tag("resultado", resultado)
                .register(registro)
                .record(duracao);
    }

    /** Tokens cobrados pelo provedor numa requisicao: a unidade de custo dos dois. */
    public void tokens(String provedor, Integer entrada, Integer saida) {
        if (entrada != null) {
            contadorDeTokens(provedor, "entrada").increment(entrada);
        }
        if (saida != null) {
            contadorDeTokens(provedor, "saida").increment(saida);
        }
    }

    private Counter contadorDeTokens(String provedor, String tipo) {
        return Counter.builder("triagem.provedor.tokens")
                .description("Tokens cobrados pelos provedores de IA")
                .tag("provedor", provedor)
                .tag("tipo", tipo)
                .register(registro);
    }

    /**
     * Provedor pulado sem requisicao: circuito aberto ou orcamento do dia
     * esgotado. Nao aparece em {@code triagem.provedor.requisicoes}, porque
     * nenhuma requisicao saiu.
     */
    public void provedorPulado(String provedor, String motivo) {
        Counter.builder("triagem.provedor.pulados")
                .description("Vezes em que um provedor foi pulado sem requisicao")
                .tag("provedor", provedor)
                .tag("motivo", motivo)
                .register(registro)
                .increment();
    }

    /** Resposta recusada pela validacao, na primeira tentativa ou na correcao. */
    public void respostaInvalida(String provedor, String tentativa) {
        Counter.builder("triagem.respostas.invalidas")
                .description("Respostas dos provedores recusadas pela validacao")
                .tag("provedor", provedor)
                .tag("tentativa", tentativa)
                .register(registro)
                .increment();
    }

    /**
     * Consulta ao cache. No acerto, {@code provedor} e quem classificou o texto
     * originalmente — o custo que o cache evitou e daquele provedor.
     */
    public void consultaAoCache(boolean acerto, String provedor) {
        Counter.builder("triagem.cache")
                .description("Consultas ao cache de classificacoes")
                .tag("resultado", acerto ? "acerto" : "falta")
                .tag("provedor", acerto ? provedor : "nenhum")
                .register(registro)
                .increment();
    }

    /**
     * Uma rodada de triagem de ponta a ponta, da reserva ao resultado.
     *
     * @param provedor quem classificou, ou {@code nenhum} quando ficou pendente
     * @param fallback se o principal falhou e outro respondeu
     * @param cache    se a classificacao veio do cache
     */
    public void triagem(String resultado, String provedor, boolean fallback, boolean cache, Duration duracao) {
        Timer.builder("triagem.triagens")
                .description("Rodadas de triagem, da reserva ao resultado")
                .tag("resultado", resultado)
                .tag("provedor", provedor)
                .tag("fallback", String.valueOf(fallback))
                .tag("cache", String.valueOf(cache))
                .register(registro)
                .record(duracao);
    }

    public void chamadoRegistrado() {
        chamadosRegistrados.increment();
    }

    public void chamadoRecusado(String motivo) {
        Counter.builder("triagem.chamados.recusados")
                .description("Chamados recusados antes de serem gravados")
                .tag("motivo", motivo)
                .register(registro)
                .increment();
    }

    /** Nome de etiqueta a partir de um enum: minusculo, como o resto das etiquetas. */
    public static String etiqueta(Enum<?> valor) {
        return valor.name().toLowerCase(Locale.ROOT);
    }
}
