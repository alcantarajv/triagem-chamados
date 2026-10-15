# Triagem de Chamados com IA resiliente

API REST que recebe chamados de suporte em texto livre e usa um modelo de linguagem para classificá-los — categoria, prioridade e um resumo de uma linha —, construída em Java 21 com Spring Boot 4, Spring AI e PostgreSQL.

O que este projeto resolve não é o prompt. É a engenharia de backend em volta de uma dependência que **demora, falha, cobra por uso e às vezes responde algo fora do combinado**.

> **Status:** em construção — Etapa 1 de 13. O [roadmap](#roadmap) mostra o que já está pronto e o que vem a seguir.

---

## Sumário

- [O problema central](#o-problema-central)
- [Decisões técnicas](#decisões-técnicas)
- [Como rodar](#como-rodar)
- [Testes planejados](#testes-planejados)
- [Stack](#stack)
- [Roadmap](#roadmap)
- [Projetos relacionados](#projetos-relacionados)
- [Autor](#autor)
- [Licença](#licença)

---

## O problema central

> **O que acontece com a triagem quando o provedor de IA demora 30 segundos, cai, estoura a cota ou responde algo que não está no formato pedido?**

A versão ingênua chama o provedor dentro da requisição do cliente e devolve o que vier:

```java
@PostMapping("/api/chamados")
Chamado criar(@RequestBody NovoChamado novo) {
    Triagem triagem = modelo.classificar(novo.texto());   // <-- o cliente espera por isto
    return repositorio.save(new Chamado(novo, triagem));
}
```

Ela funciona no teste com o provedor no ar e tem cinco defeitos que só aparecem em produção:

| # | Defeito | Consequência |
|---|---|---|
| 1 | A latência do provedor vira a latência da API | se o modelo leva 20 segundos, o cliente espera 20 segundos |
| 2 | Uma falha do provedor vira um erro 500 | o chamado se perde |
| 3 | Repetir sem critério | tentar de novo um `429` sem respeitar o `Retry-After` só consome a cota mais rápido |
| 4 | A saída do modelo é tratada como confiável | um JSON malformado ou uma categoria inventada chegam ao banco |
| 5 | Não há controle de custo | o mesmo texto classificado mil vezes custa mil chamadas |

O projeto resolve cada um desses pontos, e cada solução terá um teste que simula a falha correspondente.

---

## Decisões técnicas

Esta seção cresce a cada etapa. Cada entrada registra a escolha, o motivo e a alternativa descartada, e só entra aqui quando estiver implementada.

### Nenhum segredo no repositório

As chaves dos provedores de IA entram só por variável de ambiente: um arquivo `.env` local, fora do Git, e o painel da plataforma de deploy. O CI não precisa de chave nenhuma, porque nenhum teste automatizado chama um provedor real — um servidor simulado faz esse papel.

### Cada dependência entra na etapa em que é usada

O scaffold não traz Spring AI nem Resilience4j. O starter do Spring AI exige a chave do provedor para o contexto subir; adicioná-lo antes de existir código que o use faria a aplicação — e o teste de contexto no CI — depender de um segredo sem motivo. O `ContextoDaAplicacaoIT` guarda essa regra: ele sobe a aplicação sem chave nenhuma e quebra no dia em que alguma dependência passar a exigir uma.

### O schema pertence ao Flyway, não ao Hibernate

`spring.jpa.hibernate.ddl-auto=validate`: o Hibernate confere se o mapeamento bate com as tabelas e recusa subir se não bater, mas não cria nem altera nada. Com `update`, o schema vira consequência do código e o banco de produção passa a ter a forma que o Hibernate decidiu, sem registro de quando cada coluna nasceu. Toda mudança de schema passa por um arquivo versionado em `db/migration`.

### UTC de ponta a ponta

O Jackson serializa em UTC e o Hibernate lê e grava `TIMESTAMPTZ` em UTC (`hibernate.jdbc.time_zone`), independente do fuso da máquina. Sem a configuração do Hibernate, ele converte pelo fuso da JVM, e o mesmo instante seria gravado de um jeito no notebook e de outro no servidor.

### Testes unitários e de integração separados por plugin

O Surefire roda `*Test` (unitários, sem dependência externa) e o Failsafe roda `*IT` (integração, exigem Docker). `mvnw test` continua rápido o suficiente para rodar a cada alteração; `mvnw verify` roda a suíte inteira.

### PostgreSQL de verdade nos testes

Os testes de integração sobem o PostgreSQL em contêiner com Testcontainers, na mesma imagem do `compose.yaml`, com `@ServiceConnection` injetando as credenciais no Spring. Um banco em memória não serviria: a fila de triagem vai depender de `SELECT ... FOR UPDATE SKIP LOCKED`, e o teste com duas instâncias do worker só prova algo se o lock se comportar como no PostgreSQL de produção.

---

## Como rodar

Pré-requisitos: Java 21 e Docker. O Maven não precisa estar instalado — o wrapper (`mvnw`) baixa a versão certa.

```bash
cp .env.example .env
docker compose up -d
./mvnw spring-boot:run
```

No Windows, use `mvnw.cmd` no lugar de `./mvnw`.

A aplicação sobe em <http://localhost:8080>.

```bash
curl http://localhost:8080/actuator/health
```

O `health` agrega o estado do banco: se o PostgreSQL estiver fora, a resposta é `DOWN`.

### Sem Docker Compose

Pela IDE, a classe `TestTriagemApplication` sobe a aplicação com um PostgreSQL descartável via Testcontainers — o banco nasce com o processo e morre junto, e cada execução começa limpa.

### Testes

```bash
./mvnw verify
```

Roda os unitários e os de integração. Os de integração exigem Docker em execução.

---

## Testes planejados

Os testes são a prova do argumento central. O WireMock fará o papel dos provedores de IA, e cada falha da lista acima terá o seu teste:

| Cenário simulado | O que o teste deve comprovar |
|---|---|
| Provedor principal demora mais que o tempo limite | o secundário responde e o chamado fica triado |
| Principal responde `429` com `Retry-After` | a nova tentativa respeita a espera ou passa para o secundário |
| Principal responde JSON inválido ou categoria inexistente | há exatamente uma nova tentativa; se continuar inválido, vai para o próximo provedor |
| Todos os provedores fora | o chamado fica pendente de triagem manual e a API não responde 500 |
| N falhas seguidas no principal | o circuito abre e o principal recebe zero requisições durante a janela |
| O mesmo texto enviado duas vezes | a segunda triagem vem do cache, sem chamar o provedor |
| Duas instâncias do worker ao mesmo tempo | cada chamado é triado uma única vez |

---

## Stack

Java 21 · Spring Boot 4 · Spring AI · Google Gemini · Groq · PostgreSQL · Flyway · Micrometer · JUnit 5 · AssertJ · Testcontainers · WireMock · Docker · GitHub Actions · Render

---

## Roadmap

- [x] **Etapa 0** — Repositório: README, `.gitignore`, licença e primeiro commit
- [x] **Etapa 1** — Scaffold Spring Boot, Actuator, Docker Compose com PostgreSQL e estrutura de pacotes
- [ ] **Etapa 2** — Domínio sem IA: `Chamado`, estados, Flyway, `POST` (202) e `GET`, Problem Details
- [ ] **Etapa 3** — Primeiro provedor (Gemini) com saída estruturada e worker assíncrono com `SKIP LOCKED`
- [ ] **Etapa 4** — Validação da saída do modelo, com uma nova tentativa informando o erro
- [ ] **Etapa 5** — Tempo limite e nova tentativa só para erros transitórios, respeitando o `Retry-After`
- [ ] **Etapa 6** — Segundo provedor (Groq), cadeia de fallback e circuit breaker por provedor
- [ ] **Etapa 7** — Degradação: triagem manual quando todos os provedores falham e job de nova tentativa
- [ ] **Etapa 8** — Controle de custo: cache por texto normalizado, limite por cliente e orçamento diário
- [ ] **Etapa 9** — Métricas por provedor com Micrometer e Prometheus
- [ ] **Etapa 10** — Página de demonstração
- [ ] **Etapa 11** — Dockerfile, CI no GitHub Actions e deploy no Render
- [ ] **Etapa 12** — README final
- [ ] **Etapa 13** — Divulgação

---

## Projetos relacionados

Este é o quarto de uma série, e cada um ataca um problema de backend que só aparece em produção:

1. [encurtador-links](https://github.com/alcantarajv/encurtador-links) — **latência**
2. [reserva-quadras](https://github.com/alcantarajv/reserva-quadras) — **concorrência**
3. [api-pedidos](https://github.com/alcantarajv/api-pedidos) — **consistência entre sistemas**
4. **triagem-chamados** — **resiliência a uma dependência externa lenta, cara e instável**

---

## Autor

**João Vitor Alcântara Corrêa**
[joaoalcantara.dev](https://joaoalcantara.dev) · [GitHub](https://github.com/alcantarajv) · [LinkedIn](https://linkedin.com/in/joaovalcantara)

---

## Licença

Distribuído sob a licença MIT. Veja [LICENSE](LICENSE).
