package com.zoufx.ai.agent.vector.config;

import com.zoufx.ai.agent.vector.property.EmbeddingProps;
import com.zoufx.ai.agent.vector.property.RecallProps;
import com.zoufx.ai.agent.vector.property.VectorStoreProps;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语义召回的基础设施装配：Embedding 模型（OpenAI 兼容 BGE-M3）+ Qdrant 向量库。
 *
 * <p>Embedding 与 LLM profile 解耦——恒走 OpenAI 兼容协议。Qdrant collection 启动时幂等确保存在
 * （不存在则按维度 + 距离建）；已存在的 collection 不做维度回查——换维度须手动重建 collection。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({EmbeddingProps.class, VectorStoreProps.class, RecallProps.class})
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel(EmbeddingProps p) {
        log.info("Creating embeddingModel baseUrl={} model={} dim={}", p.getBaseUrl(), p.getModel(), p.getDimension());
        return OpenAiEmbeddingModel.builder()
                .baseUrl(p.getBaseUrl())
                .apiKey(p.getApiKey())
                .modelName(p.getModel())
                .timeout(p.getTimeout())
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /** Qdrant gRPC 客户端；AutoCloseable，Spring 关闭时自动 close。 */
    @SuppressWarnings("null")
    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(VectorStoreProps p) {
        QdrantGrpcClient.Builder b = QdrantGrpcClient.newBuilder(p.getHost(), p.getPort(), p.isUseTls());
        if (p.getApiKey() != null && !p.getApiKey().isBlank()) {
            b.withApiKey(p.getApiKey());
        }
        log.info("Connecting Qdrant {}:{} (tls={})", p.getHost(), p.getPort(), p.isUseTls());
        return new QdrantClient(b.build());
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(QdrantClient client, VectorStoreProps p) throws Exception {
        ensureCollection(client, p);
        return QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(p.getCollection())
                .build();
    }

    /**
     * 幂等确保 collection 存在：不存在则按维度 + cosine 建；已存在则沿用。
     * 不做维度回查——换维度须先手动删除 collection 后重启重建。
     */
    @SuppressWarnings("null")
    private void ensureCollection(QdrantClient client, VectorStoreProps p) throws Exception {
        if (Boolean.TRUE.equals(client.collectionExistsAsync(p.getCollection()).get())) {
            log.info("Qdrant collection '{}' already exists", p.getCollection());
            return;
        }
        client.createCollectionAsync(p.getCollection(),
                Collections.VectorParams.newBuilder()
                        .setSize(p.getDimension())
                        .setDistance(distanceOf(p.getDistance()))
                        .build()).get();
        log.info("Qdrant collection '{}' created (dim={}, distance={})",
                p.getCollection(), p.getDimension(), p.getDistance());
    }

    private Collections.Distance distanceOf(String d) {
        return switch (d == null ? "cosine" : d.toLowerCase()) {
            case "dot" -> Collections.Distance.Dot;
            case "euclid", "euclidean" -> Collections.Distance.Euclid;
            case "manhattan" -> Collections.Distance.Manhattan;
            default -> Collections.Distance.Cosine;
        };
    }
}
