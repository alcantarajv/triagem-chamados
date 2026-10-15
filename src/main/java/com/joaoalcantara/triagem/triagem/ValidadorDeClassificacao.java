package com.joaoalcantara.triagem.triagem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.chamado.Categoria;
import com.joaoalcantara.triagem.chamado.Classificacao;
import com.joaoalcantara.triagem.chamado.Prioridade;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decide se a resposta do modelo pode virar uma triagem.
 *
 * <p>A saida estruturada nativa ja pede ao provedor que respeite o esquema, mas
 * pedir nao e garantir: o modelo pode devolver texto solto, cortar o JSON no
 * meio por limite de tokens ou inventar uma categoria — e um provedor diferente
 * pode nem aplicar o esquema. Nada do que vem do modelo entra no banco sem
 * passar por aqui.</p>
 *
 * <p>Tolerante na forma, rigoroso no conteudo: aceita {@code "tecnico"} por
 * {@code TECNICO} e o JSON embrulhado em bloco de codigo Markdown, porque
 * recusar isso custaria uma nova chamada paga sem ganho nenhum. Mas uma
 * categoria fora da lista, um resumo vazio, longo demais ou com quebra de linha
 * sao recusados.</p>
 *
 * <p>Todos os erros sao coletados, e nao so o primeiro: a lista volta para o
 * modelo na nova tentativa, e corrigir um erro por vez gastaria uma chamada por
 * erro.</p>
 */
@Component
public class ValidadorDeClassificacao {

    /** Tamanho maximo de um valor ecoado numa mensagem de erro. */
    private static final int ECO_MAXIMO = 30;

    private final JsonMapper json = JsonMapper.builder().build();

    public ResultadoDaValidacao validar(String resposta) {
        if (resposta == null || resposta.isBlank()) {
            return new ResultadoDaValidacao.Invalida(List.of("a resposta veio vazia"));
        }

        JsonNode raiz;
        try {
            raiz = json.readTree(semBlocoDeCodigo(resposta.strip()));
        } catch (JacksonException e) {
            return new ResultadoDaValidacao.Invalida(List.of("a resposta nao e um JSON valido"));
        }
        if (raiz == null || !raiz.isObject()) {
            return new ResultadoDaValidacao.Invalida(List.of("a resposta nao e um objeto JSON"));
        }

        List<String> erros = new ArrayList<>();
        Optional<Categoria> categoria = valorDoEnum(raiz, "categoria", Categoria.class, erros);
        Optional<Prioridade> prioridade = valorDoEnum(raiz, "prioridade", Prioridade.class, erros);
        Optional<String> resumo = resumo(raiz, erros);

        if (!erros.isEmpty()) {
            return new ResultadoDaValidacao.Invalida(erros);
        }
        return new ResultadoDaValidacao.Valida(
                new Classificacao(categoria.orElseThrow(), prioridade.orElseThrow(), resumo.orElseThrow()));
    }

    private <E extends Enum<E>> Optional<E> valorDoEnum(JsonNode raiz, String campo, Class<E> tipo, List<String> erros) {
        JsonNode no = raiz.get(campo);
        if (no == null || no.isNull()) {
            erros.add("falta o campo %s".formatted(campo));
            return Optional.empty();
        }
        String valor = no.isString() ? no.asString().strip() : no.toString();
        for (E constante : tipo.getEnumConstants()) {
            if (constante.name().equals(valor.toUpperCase(Locale.ROOT))) {
                return Optional.of(constante);
            }
        }
        erros.add("%s '%s' nao existe; use um destes valores: %s"
                .formatted(campo, eco(valor), valoresAceitos(tipo)));
        return Optional.empty();
    }

    private Optional<String> resumo(JsonNode raiz, List<String> erros) {
        JsonNode no = raiz.get("resumo");
        if (no == null || no.isNull()) {
            erros.add("falta o campo resumo");
            return Optional.empty();
        }
        if (!no.isString()) {
            erros.add("o resumo deve ser um texto");
            return Optional.empty();
        }
        String resumo = no.asString().strip();
        if (resumo.isEmpty()) {
            erros.add("o resumo esta vazio");
            return Optional.empty();
        }
        // O conteudo do resumo nunca e ecoado: ele deriva do texto do chamado e
        // poderia levar dados pessoais para o log.
        boolean valido = true;
        if (resumo.length() > Classificacao.TAMANHO_MAXIMO_DO_RESUMO) {
            erros.add("o resumo tem %d caracteres; o maximo e %d"
                    .formatted(resumo.length(), Classificacao.TAMANHO_MAXIMO_DO_RESUMO));
            valido = false;
        }
        if (resumo.contains("\n") || resumo.contains("\r")) {
            erros.add("o resumo deve ter uma unica linha");
            valido = false;
        }
        return valido ? Optional.of(resumo) : Optional.empty();
    }

    /** Aceita o JSON dentro de um bloco ```json ... ```, que alguns modelos insistem em usar. */
    private static String semBlocoDeCodigo(String resposta) {
        if (!resposta.startsWith("```")) {
            return resposta;
        }
        int inicioDoConteudo = resposta.indexOf('\n');
        int fim = resposta.lastIndexOf("```");
        if (inicioDoConteudo < 0 || fim <= inicioDoConteudo) {
            return resposta;
        }
        return resposta.substring(inicioDoConteudo + 1, fim).strip();
    }

    private static String eco(String valor) {
        String umaLinha = valor.replaceAll("\\s+", " ");
        return umaLinha.length() <= ECO_MAXIMO ? umaLinha : umaLinha.substring(0, ECO_MAXIMO) + "...";
    }

    private static <E extends Enum<E>> String valoresAceitos(Class<E> tipo) {
        return Arrays.stream(tipo.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
