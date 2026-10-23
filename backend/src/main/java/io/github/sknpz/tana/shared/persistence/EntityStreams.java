package io.github.sknpz.tana.shared.persistence;

import java.util.stream.Stream;

import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;

/**
 * Leitura em fluxo para as exportações: o banco entrega as linhas aos poucos e cada entidade sai do
 * contexto de persistência assim que é consumida, então a memória não cresce com o tamanho da tabela.
 */
public final class EntityStreams {

    /** Linhas que o driver busca por vez. Sem isso o PostgreSQL traz o resultado inteiro para a memória. */
    private static final int FETCH_SIZE = 500;

    private EntityStreams() {
    }

    /**
     * Entidades que atendem à especificação, ordenadas por {@code sort}. As associações em
     * {@code fetchPaths} (ex.: "category") vêm no mesmo SELECT, evitando o problema N+1.
     * O stream deve ser consumido e fechado dentro da transação.
     */
    public static <T> Stream<T> stream(EntityManager em, Class<T> type, Specification<T> spec, Sort sort,
            String... fetchPaths) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(type);
        Root<T> root = query.from(type);
        for (String path : fetchPaths) {
            root.fetch(path, JoinType.LEFT);
        }
        query.select(root).where(spec.toPredicate(root, query, cb));
        query.orderBy(sort.stream()
                .map(order -> order.isAscending() ? cb.asc(root.get(order.getProperty()))
                        : cb.desc(root.get(order.getProperty())))
                .toArray(Order[]::new));

        return em.createQuery(query)
                .setHint(HibernateHints.HINT_FETCH_SIZE, FETCH_SIZE)
                .getResultStream()
                .map(entity -> {
                    // Os dados já foram lidos; soltar a entidade evita acumulá-las até o fim da transação
                    em.detach(entity);
                    return entity;
                });
    }
}
