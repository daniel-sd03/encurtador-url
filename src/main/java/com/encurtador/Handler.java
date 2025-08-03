package com.encurtador;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
	
	private static final String BUCKET_NAME = "";
	private static final Region REGION = Region.US_EAST_1;
	private static final String S3_WEBSITE_ORIGIN =  "";
	private static final String REDIRECT_BASE_URL =  S3_WEBSITE_ORIGIN + "";
		
	// Iniciando conexão com S3
	private static final S3Client s3 = S3Client.builder()
			.region(REGION)
			.credentialsProvider(DefaultCredentialsProvider.create())
			.build();

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
		int tamanhoUrl = 3;
		int tamanhoMaxUrl = 5;

		//Pegando corpo da requisição
	    String body = (String) input.get("body");
	    if (body == null || body.isEmpty()) {
	        return criarErro(400, "Corpo da requisição vazio.");
	    }
	    
	    //transformando corpo da requisição em json
	    JSONObject json = new JSONObject(body);
	    
	    //pegando url enviada
	    String originalUrl = json.optString("url", "");
	    
	    //Vericando se a url enviada não está vazia
	    if (originalUrl.isEmpty()) {
	        return criarErro(400, "A URL está vazia ou nula.");
	    }

		// Transformando a url original em bytes
		byte[] hashBytes;
		try {
			hashBytes = transformarHashBytes(originalUrl);
		} catch (NoSuchAlgorithmException e) {
			return criarErro(500, "Erro ao gerar hash da URL: " + e.getMessage());
		}

		// loop para garantir que a url curta não se repita no S3
		do {
			try {
				// EncurtandoURL
				String curtaUrl = encurtarUrl(hashBytes, tamanhoUrl);

				// montagem do escopo para enviar os dados para o S3
				PutObjectRequest request = PutObjectRequest.builder()
						.bucket(BUCKET_NAME)
						.key("encurtado/" + curtaUrl)
						.websiteRedirectLocation(originalUrl)
						.metadata(Map.of("redirect-url", originalUrl))
						.contentLength(0L)
						.overrideConfiguration(cfg -> cfg.putHeader("If-None-Match", "*"))
						.build();

				// Enviando para o S3
				s3.putObject(request, RequestBody.empty());

				// montando resposta
				String urlCurtaFinal = REDIRECT_BASE_URL + curtaUrl;
				return criarMsgSucesso(originalUrl, urlCurtaFinal);
				
			} catch (S3Exception e) {
				// verifica se o erro foi devido a uma url já existente
				if (e.statusCode() == 412) {
					// aumentando o tamnaho da url para gerar uma url diferente da já existente
					tamanhoUrl++;
				} else {
					return criarErro(500, "Erro do S3: " + e.awsErrorDetails().errorMessage());
				}
			} catch (SdkException e) {
				return criarErro(500, "Erro da SDK: " + e.getMessage());
			}
		} while (tamanhoUrl < tamanhoMaxUrl);
		return criarErro(409, "Não foi possível gerar uma URL curta única após várias tentativas.");
	}

	public String encurtarUrl(byte[] hashBytes, int tamanhoURL) {
		StringBuilder sb = new StringBuilder();
		for (int x = 0; x < tamanhoURL; x++) {
			// Converte byte em string hexadecimal com dois dígitos
			sb.append(String.format("%02x", hashBytes[x]));
		}
		return sb.toString();
	}

	public byte[] transformarHashBytes(String urlOriginal) throws NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] hashBytes = digest.digest(urlOriginal.getBytes(StandardCharsets.UTF_8));
		return hashBytes;
	}

	private Map<String, Object> criarErro(int statusCode, String mensagem) {
		Map<String, Object> erro = new HashMap<>();
		
		Map<String, String> headers = criarHeaders();
		
		erro.put("statusCode", statusCode);
		erro.put("headers", headers);
		erro.put("body", new JSONObject(Map.of("erro", mensagem)).toString());
		
		return erro;
	}
	
	private Map<String, Object> criarMsgSucesso(String urlOriginal, String urlCurta) {
		Map<String, Object> sucesso = new HashMap<>();
		
		Map<String, String> headers = criarHeaders();
		
		JSONObject bodyJson = new JSONObject();
		bodyJson.put("urlOriginal", urlOriginal);
		bodyJson.put("urlCurta", urlCurta);
		
		sucesso.put("statusCode", 200);
		sucesso.put("headers", headers);
		sucesso.put("body", bodyJson.toString());

		return sucesso;
	}
	
	private Map<String, String> criarHeaders(){
	    Map<String, String> headers = new HashMap<>();
	    headers.put("Content-Type", "application/json");
	    //CORS, permissão apenas para encurtador.html
	    headers.put("Access-Control-Allow-Origin", S3_WEBSITE_ORIGIN);
	    return headers;
	}
}