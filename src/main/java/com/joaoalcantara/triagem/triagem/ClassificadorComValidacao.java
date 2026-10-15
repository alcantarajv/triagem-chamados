package com.joaoalcantara.triagem.triagem;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.chamado.Classificacao;
import com.joaoalcantara.triagem.custo.CacheDeClassificacao;
import com.joaoalcantara.triagem.infra.MetricasDaTriagem;
import com.joaoalcantara.triagem.provedor.FalhaNoProvedorException;
import com.joaoalcantara.triagem.provedor.ProvedorDeIa;
import com.joaoalcantara.triagem.provedor.ProvedoresDeIa;

/**
 * Percorre a cadeia de provedores ate obter uma classificacao validada.
 *
 * <p>Cada provedor tem a sua chance completa — as novas tentativas para falha
 * transitoria e a correcao de resposta invalida — antes de a vez passar para o
 * seguinte. Se nenhum resolver, a triagem automatica desiste e o chamado vai
 * para triagem manual.</p>
 *
 * <p>Resposta invalida ganha <b>uma</b> nova tentativa, com os motivos da recusa
 * no pedido. Uma, e nao "ate dar certo": o erro de formato costuma ser pontual,
 * e uma correcao com o erro apontado resolve a maioria; se o modelo erra de novo
 * mesmo sabendo o que errou, insistir com ele tende a repetir o erro e gasta
 * cota a cada volta. Melhor perguntar a outro modelo.</p>
 *
 * <p>Essa correcao vale so para resposta <i>invalida</i>. Falha de comunicacao
 * — erro HTTP, tempo esgotado, circuito aberto — sai do provedor como
 * {@link FalhaNoProvedorException}, ja depois das novas tentativas que valiam a
 * pena, e a vez passa direto ao proximo.</p>
 */
@Component
public class ClassificadorComValidacao {

    private static final Logger log = LoggerFactory.getLogger(ClassificadorComValidacao.class);

    private final ProvedoresDeIa provedores;
    private final ValidadorDeClassificacao validador;
    private final CacheDeClassificacao cache;
    private final MetricasDaTriagem metricas;

    public ClassificadorComValidacao(ProvedoresDeIa provedores, ValidadorDeClassificacao validador,
                                     CacheDeClassificacao cache, MetricasDaTriagem metricas) {
        this.provedores = provedores;
        this.validador = validador;
        this.cache = cache;
        this.metricas = metricas;
    }

    /**
     * Classificacao aceita, o provedor que a produziu, os que falharam antes
     * dele e se ela veio do cache. {@code provedoresQueFalharam} vazio significa
     * que o principal resolveu; qualquer outra coisa e fallback.
     */
    public record TriagemObtida(Classificacao classificacao, String provedor, List<String> provedoresQueFalharam,
                                boolean doCache) {

        public TriagemObtida {
            provedoresQueFalharam = List.copyOf(provedoresQueFalharam);
        }

        public boolean houveFallback() {
            return !provedoresQueFalharam.isEmpty();
        }
    }

    /**
     * Consulta o cache antes de qualquer provedor: texto ja classificado nao
     * custa outra chamada. So o que passou na validacao vai para o cache.
     *
     * @throws TodosOsProvedoresFalharamException se nenhum provedor resolver
     */
    public TriagemObtida classificar(String texto) {
        var guardada = cache.buscar(texto);
        metricas.consultaAoCache(guardada.isPresent(), guardada.map(CacheDeClassificacao.ClassificacaoGuardada::provedor)
                .orElse(null));
        if (guardada.isPresent()) {
            return new TriagemObtida(guardada.get().classificacao(), guardada.get().provedor(), List.of(), true);
        }

        List<String> falharam = new ArrayList<>();
        List<String> motivos = new ArrayList<>();
        for (ProvedorDeIa provedor : provedores.emOrdem()) {
            try {
                Classificacao classificacao = classificarCom(provedor, texto);
                cache.guardar(texto, classificacao, provedor.nome());
                return new TriagemObtida(classificacao, provedor.nome(), falharam, false);
            } catch (FalhaNoProvedorException | RespostaInvalidaException e) {
                log.warn("Provedor {} nao resolveu, passando a vez: {}", provedor.nome(), e.getMessage());
                falharam.add(provedor.nome());
                motivos.add(e.getMessage());
            }
        }
        throw new TodosOsProvedoresFalharamException(motivos);
    }

    private Classificacao classificarCom(ProvedorDeIa provedor, String texto) {
        String resposta = provedor.classificar(texto);
        ResultadoDaValidacao primeira = validador.validar(resposta);
        if (primeira instanceof ResultadoDaValidacao.Valida valida) {
            return valida.classificacao();
        }

        var erros = ((ResultadoDaValidacao.Invalida) primeira).erros();
        metricas.respostaInvalida(provedor.nome(), "primeira");
        log.info("Resposta de {} recusada, pedindo correcao: {}", provedor.nome(), erros);

        String corrigida = provedor.corrigir(texto, resposta, erros);
        return switch (validador.validar(corrigida)) {
            case ResultadoDaValidacao.Valida valida -> valida.classificacao();
            case ResultadoDaValidacao.Invalida invalida -> {
                metricas.respostaInvalida(provedor.nome(), "correcao");
                throw new RespostaInvalidaException(provedor.nome(), invalida.erros());
            }
        };
    }
}
