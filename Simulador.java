import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Alunos: Joao Nunes e Romulo Pitt
 *
 * Uso:
 *   java Simulador.java <arquivo.yml>
 */
public class Simulador {

    //constantes de util.Random, mas usa a seed diretamente para bater com o simulador do m3.
    static class LCG {
        static long M = 281474976710656L;
        static long M_MASK = M - 1L;
        static long A = 25214903917L;
        static long C = 11L;

        long seed;
        long count = 0;
        long limite;

        LCG(long seed, long limite) {
            this.seed = seed;
            this.limite = limite;
        }

        boolean hasNext() { return count < limite; }

        double getNext() {
            count++;
            seed = ((A * seed) + C) & M_MASK;
            return (double) seed / (double) M;
        }
    }


    enum TipoEvento { CHEGADA, SAIDA }

    static class Evento implements Comparable<Evento> {
        double tempo;
        TipoEvento tipo;
        String fila;
        String destino;
        boolean externa; //true = chegada externa
        long ordem; //desempate

        Evento(double tempo, TipoEvento tipo, String fila, String destino,
               boolean externa, long ordem) {
            this.tempo = tempo;
            this.tipo = tipo;
            this.fila = fila;
            this.destino = destino;
            this.externa = externa;
            this.ordem = ordem;
        }

        @Override
        public int compareTo(Evento o) {
            int c = Double.compare(this.tempo, o.tempo);
            if (c != 0) {
                return c;
            } else {
                return Long.compare(this.ordem, o.ordem);
            }
        }
    }

    static class Fila {
        String id;
        int servidores;
        int capacidade; //-1 = infinita
        double minChegada, maxChegada; //-1 se nao ha chegada
        double minServico, maxServico;

        //roteamento, Saida eh o que sobra
        List<String> destinosId = new ArrayList<>();
        List<Double> destinosProb = new ArrayList<>();

        int pop = 0;
        double tempoAtual = 0;
        double ultimoEvento = 0;
        Map<Integer, Double> tempoPorEstado = new LinkedHashMap<>();

        int chegados = 0;
        int perdidos = 0;
        Map<String, Integer> destinosStats = new LinkedHashMap<>();

        RedeFilas rede;

        Fila(String id, int servidores, Integer capacidade,
             Double minChegada, Double maxChegada,
             double minServico, double maxServico) {
            this.id = id;
            this.servidores = servidores;
            if (capacidade == null) {
                this.capacidade = -1;
            } else {
                this.capacidade = capacidade;
            }
            if (minChegada == null) {
                this.minChegada = -1;
            } else {
                this.minChegada = minChegada;
            }
            if (maxChegada == null) {
                this.maxChegada = -1;
            } else {
                this.maxChegada = maxChegada;
            }
            this.minServico = minServico;
            this.maxServico = maxServico;
            this.destinosStats.put("Saida", 0);
        }

        void setDestinos(List<String> ids, List<Double> probs) {
            //ordena destinos por probabilidade (maior primeiro) para facilitar o sorteio
            Integer[] order = new Integer[ids.size()];
            for (int i = 0; i < ids.size(); i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Double.compare(probs.get(a), probs.get(b)));
            for (int i : order) {
                this.destinosId.add(ids.get(i));
                this.destinosProb.add(probs.get(i));
            }
            for (String d : ids) {
                this.destinosStats.putIfAbsent(d, 0);
            }
        }

        void atualizaTempo() {
            double anterior = tempoPorEstado.getOrDefault(pop, 0.0);
            tempoPorEstado.put(pop, anterior + (tempoAtual - ultimoEvento));
        }

        String sorteiaDestino() {
            if (destinosId.isEmpty()) {
                destinosStats.merge("Saida", 1, Integer::sum);
                return null;
            }
            double r = rede.lcg.getNext();
            double cum = 0.0;
            for (int i = 0; i < destinosId.size(); i++) {
                cum += destinosProb.get(i);
                if (r < cum) {
                    String d = destinosId.get(i);
                    destinosStats.merge(d, 1, Integer::sum);
                    return d;
                }
            }
            destinosStats.merge("Saida", 1, Integer::sum);
            return null;
        }

        double tempoServico(double t) {
            return t + (maxServico - minServico) * rede.lcg.getNext() + minServico;
        }

        double tempoChegada(double t) {
            return t + (maxChegada - minChegada) * rede.lcg.getNext() + minChegada;
        }

        void chegada(double t, boolean externa) {
            chegados++;

            //sem espaco = perda
            if (capacidade >= 0 && pop == capacidade) {
                perdidos++;
                if (externa) {
                    double te = tempoChegada(t);
                    rede.agenda(new Evento(te, TipoEvento.CHEGADA, id, null, true, rede.proxOrdem()));
                }
                return;
            }

            tempoAtual = t;
            atualizaTempo();
            pop++;
            ultimoEvento = t;

            if (pop <= servidores) {
                //servidor livre, ja sorteia destino e tempo de servico
                //gerando o evento de saida deste cliente
                String destino = sorteiaDestino();
                double te = tempoServico(t);
                rede.agenda(new Evento(te, TipoEvento.SAIDA, id, destino, false, rede.proxOrdem()));
            }

            if (externa) {
                //reagenda proxima chegada externa
                double te = tempoChegada(t);
                rede.agenda(new Evento(te, TipoEvento.CHEGADA, id, null, true, rede.proxOrdem()));
            }
        }

        void saida(double t) {
            tempoAtual = t;
            atualizaTempo();
            pop--;
            ultimoEvento = t;

            if (pop >= servidores) {
                //ainda tem cliente esperando = agenda atendimento do proximo
                String destino = sorteiaDestino();
                double te = tempoServico(t);
                rede.agenda(new Evento(te, TipoEvento.SAIDA, id, destino, false, rede.proxOrdem()));
            }
        }
    }

    static class RedeFilas {
        Map<String, Fila> filas;
        PriorityQueue<Evento> escalonador = new PriorityQueue<>();
        List<Evento> eventosIniciais = new ArrayList<>();
        LCG lcg;
        double relogio = 0;
        double tempoTotal = 0;
        long ordemEvento = 0;

        RedeFilas(Map<String, Fila> filas) {
            this.filas = filas;
            for (Fila f : filas.values()) f.rede = this;
        }

        long proxOrdem() { return ordemEvento++; }

        void setLcg(LCG lcg) { this.lcg = lcg; }

        void agenda(Evento e) { escalonador.add(e); }

        void agendaInicial(Evento e) {
            escalonador.add(e);
            eventosIniciais.add(e);
        }

        void proximoEvento() {
            Evento e = escalonador.poll();
            relogio = e.tempo;
            Fila f = filas.get(e.fila);
            if (e.tipo == TipoEvento.CHEGADA) {
                f.chegada(e.tempo, e.externa);
            } else { // SAIDA
                f.saida(e.tempo);
                if (e.destino != null) {
                    Fila d = filas.get(e.destino);
                    d.chegada(e.tempo, false);
                }
            }
        }

        void encerraReplicacao() {
            for (Fila f : filas.values()) {
                f.tempoAtual = relogio;
                f.atualizaTempo();
                f.tempoAtual = 0;
                f.ultimoEvento = 0;
                f.pop = 0;
            }
            tempoTotal += relogio;
            relogio = 0;
            escalonador.clear();
            for (Evento e : eventosIniciais) escalonador.add(e);
        }
    }

    //parser yaml
    static class SimpleYaml {
        public List<String> linhas;
        public int idx = 0;

        public SimpleYaml(List<String> linhas) { this.linhas = linhas; }

        static Map<String, Object> parse(Path path) throws IOException {
            List<String> raw = Files.readAllLines(path);
            List<String> ls = new ArrayList<>();
            for (String r : raw) {
                int hash = r.indexOf('#');
                String s;
                if (hash >= 0) {
                    s = r.substring(0, hash);
                } else {
                    s = r;
                }
                if (s.trim().isEmpty()) continue;
                if (s.trim().startsWith("!")) continue; // diretiva !PARAMETERS
                int end = s.length();
                while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
                ls.add(s.substring(0, end));
            }
            SimpleYaml y = new SimpleYaml(ls);
            return y.parseMapa(0);
        }

        public static int indentacao(String linha) {
            int i = 0;
            while (i < linha.length() && linha.charAt(i) == ' ') i++;
            return i;
        }

        public static Object parseEscalar(String s) {
            s = s.trim();
            if (s.isEmpty()) return null;
            if (s.matches("[+-]?\\d+")) return Long.parseLong(s);
            if (s.matches("[+-]?(\\d+\\.\\d*|\\.\\d+)([eE][+-]?\\d+)?|[+-]?\\d+[eE][+-]?\\d+")) {
                return Double.parseDouble(s);
            }
            return s;
        }

        public Object parseValor(int parentIndent) {
            if (idx >= linhas.size()) return null;
            String first = linhas.get(idx);
            int ind = indentacao(first);
            boolean isLista = first.trim().startsWith("-");
            if (isLista) {
                if (ind < parentIndent) return null;
                return parseLista(ind);
            }
            if (ind <= parentIndent) return null;
            return parseMapa(ind);
        }

        public Map<String, Object> parseMapa(int mapIndent) {
            Map<String, Object> mapa = new LinkedHashMap<>();
            while (idx < linhas.size()) {
                String linha = linhas.get(idx);
                int ind = indentacao(linha);
                if (ind != mapIndent) break;
                if (linha.trim().startsWith("-")) break;
                int colon = linha.indexOf(':');
                if (colon < 0) break;
                String chave = linha.substring(0, colon).trim();
                String valor = linha.substring(colon + 1).trim();
                idx++;
                if (valor.isEmpty()) {
                    mapa.put(chave, parseValor(mapIndent));
                } else {
                    mapa.put(chave, parseEscalar(valor));
                }
            }
            return mapa;
        }

        public List<Object> parseLista(int listIndent) {
            List<Object> lista = new ArrayList<>();
            while (idx < linhas.size()) {
                String linha = linhas.get(idx);
                int ind = indentacao(linha);
                if (ind != listIndent) break;
                if (!linha.trim().startsWith("-")) break;

                int dashCol = ind;
                int p = dashCol + 1;
                while (p < linha.length() && linha.charAt(p) == ' ') p++;
                String conteudo;
                if (p < linha.length()) {
                    conteudo = linha.substring(p);
                } else {
                    conteudo = "";
                }
                int innerIndent = p;

                if (conteudo.isEmpty()) {
                    idx++;
                    lista.add(parseValor(listIndent));
                } else if (conteudo.contains(":")) {
                    //mapa inline no item da lista
                    Map<String, Object> m = new LinkedHashMap<>();
                    int colon = conteudo.indexOf(':');
                    String chave = conteudo.substring(0, colon).trim();
                    String valor = conteudo.substring(colon + 1).trim();
                    idx++;
                    if (valor.isEmpty()) {
                        m.put(chave, parseValor(innerIndent));
                    } else {
                        m.put(chave, parseEscalar(valor));
                    }
                    //continua o mapa nas linhas seguintes em innerIndent
                    while (idx < linhas.size()) {
                        String l = linhas.get(idx);
                        int li = indentacao(l);
                        if (li != innerIndent) break;
                        if (l.trim().startsWith("-")) break;
                        int c = l.indexOf(':');
                        if (c < 0) break;
                        String k = l.substring(0, c).trim();
                        String v = l.substring(c + 1).trim();
                        idx++;
                        if (v.isEmpty()) {
                            m.put(k, parseValor(innerIndent));
                        } else {
                            m.put(k, parseEscalar(v));
                        }
                    }
                    lista.add(m);
                } else {
                    lista.add(parseEscalar(conteudo));
                    idx++;
                }
            }
            return lista;
        }
    }

    public static void main(String[] args) throws IOException {

        Map<String, Object> dados = SimpleYaml.parse(Paths.get(args[0]));

        //suprimi avisos de cast
        @SuppressWarnings("unchecked")
        Map<String, Object> queuesYml = (Map<String, Object>) dados.get("queues");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> network = (List<Map<String, Object>>) dados.get("network");
        @SuppressWarnings("unchecked")
        Map<String, Object> arrivals = (Map<String, Object>) dados.get("arrivals");
        @SuppressWarnings("unchecked")
        List<Object> seeds = (List<Object>) dados.get("seeds");
        long perSeed = ((Number) dados.get("rndnumbersPerSeed")).longValue();

        //constroi as filas
        Map<String, Fila> filas = new LinkedHashMap<>();
        for (var entry : queuesYml.entrySet()) {
            String qid = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> q = (Map<String, Object>) entry.getValue();
            int servers = ((Number) q.get("servers")).intValue();
            Integer capacity;
            if (q.get("capacity") != null) {
                capacity = ((Number) q.get("capacity")).intValue();
            } else {
                capacity = null;
            }
            Double minA;
            if (q.get("minArrival") != null) {
                minA = ((Number) q.get("minArrival")).doubleValue();
            } else {
                minA = null;
            }
            Double maxA;
            if (q.get("maxArrival") != null) {
                maxA = ((Number) q.get("maxArrival")).doubleValue();
            } else {
                maxA = null;
            }
            double minS = ((Number) q.get("minService")).doubleValue();
            double maxS = ((Number) q.get("maxService")).doubleValue();

            Fila fila = new Fila(qid, servers, capacity, minA, maxA, minS, maxS);

            List<String> destIds = new ArrayList<>();
            List<Double> destProbs = new ArrayList<>();
            if (network != null) {
                for (Map<String, Object> link : network) {
                    if (qid.equals(link.get("source"))) {
                        destIds.add((String) link.get("target"));
                        destProbs.add(((Number) link.get("probability")).doubleValue());
                    }
                }
            }
            fila.setDestinos(destIds, destProbs);
            filas.put(qid, fila);
        }

        RedeFilas rede = new RedeFilas(filas);

        //chegadas iniciais
        if (arrivals != null) {
            for (var entry : arrivals.entrySet()) {
                double t0 = ((Number) entry.getValue()).doubleValue();
                rede.agendaInicial(new Evento(t0, TipoEvento.CHEGADA,
                        entry.getKey(), null, true, rede.proxOrdem()));
            }
        }

        printHeader(args[0], seeds, perSeed);

        //executa para cada seed
        for (Object s : seeds) {
            long seedVal = ((Number) s).longValue();
            LCG lcg = new LCG(seedVal, perSeed);
            rede.setLcg(lcg);
            System.out.printf("Seed %d ...%n", seedVal);
            while (lcg.hasNext()) rede.proximoEvento();
            rede.encerraReplicacao();
        }

        printRelatorio(rede, seeds.size());
    }

    public static void printHeader(String arquivo, List<Object> seeds, long perSeed) {
        String linha = "-----------------";
        System.out.println(linha);
        System.out.println("Arquivo:               " + arquivo);
        System.out.println("Aleatorios por seed:   " + perSeed);
        System.out.print  ("Seeds:                 ");
        for (int i = 0; i < seeds.size(); i++) {
            if (i > 0) System.out.print(", ");
            System.out.print(seeds.get(i));
        }
        System.out.println();
        System.out.println(linha);
    }

    public static void printRelatorio(RedeFilas rede, int nSeeds) {
        String linha   = "-----------------";
        System.out.println();
        System.out.println(linha);

        double totalTempo = rede.tempoTotal + rede.relogio;

        for (Fila f : rede.filas.values()) {
            String kendall = "G/G/" + f.servidores;
            if (f.capacidade > 0) {
                kendall = kendall + "/" + f.capacidade;
            } else {
                kendall = kendall + "";
            }
            System.out.println(linha);
            System.out.println("Fila:    " + f.id + " (" + kendall + ")");
            if (f.minChegada >= 0) {
                System.out.printf("Chegada:    %.1f ... %.1f%n", f.minChegada, f.maxChegada);
            }
            System.out.printf("Servico:    %.1f ... %.1f%n", f.minServico, f.maxServico);
            System.out.println(linha);
            System.out.println("   Estado              Tempo               Probabilidade");
            //imprime em ordem numerica
            int max = -1;
            for (int k : f.tempoPorEstado.keySet()) if (k > max) max = k;
            for (int k = 0; k <= max; k++) {
                Double v = f.tempoPorEstado.get(k);
                if (v == null) continue;
                double prob;
                if (totalTempo > 0) {
                    prob = v / totalTempo * 100.0;
                } else {
                    prob = 0.0;
                }
                System.out.printf("%9d %20.4f %19.2f%%%n", k, v, prob);
            }
            //mostra para onde vao os clientes que saem da fila
            System.out.println();
            System.out.println("Roteamento:");
            for (var e : f.destinosStats.entrySet()) {
                System.out.println("   " + f.id + " -> " + e.getKey() + " : " + e.getValue());
            }
            System.out.println();
            System.out.println("Chegadas: " + f.chegados + "   Perdas: " + f.perdidos);
            System.out.println();
        }

        System.out.println(linha);
        if (nSeeds > 1) {
            System.out.printf("Tempo medio de simulacao global: %.4f%n",
                    rede.tempoTotal / nSeeds);
        } else {
            System.out.printf("Tempo global de simulacao: %.4f%n",
                    rede.tempoTotal);
        }
        System.out.println(linha);
    }
}
