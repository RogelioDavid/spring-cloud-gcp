/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gcp.vision;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.vision.v1.AnnotateFileResponse;
import com.google.cloud.vision.v1.AsyncAnnotateFileRequest;
import com.google.cloud.vision.v1.AsyncBatchAnnotateFilesResponse;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Feature.Type;
import com.google.cloud.vision.v1.GcsDestination;
import com.google.cloud.vision.v1.GcsSource;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.InputConfig;
import com.google.cloud.vision.v1.OperationMetadata;
import com.google.cloud.vision.v1.OutputConfig;
import com.google.cloud.vision.v1.TextAnnotation;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import org.springframework.cloud.gcp.storage.GoogleStorageLocation;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;

/**
 * Template providing convenient operations for interfacing with Google Cloud Vision's
 * Document OCR feature, which allows you to run OCR algorithms on documents (PDF or TIFF format)
 * stored on Google Cloud Storage.
 *
 * @author Daniel Zou
 */
public class DocumentOcrTemplate {

	private static final Pattern OUTPUT_PAGE_PATTERN = Pattern.compile("output-\\d+-to-\\d+\\.json");

	private final ImageAnnotatorClient imageAnnotatorClient;

	private final Storage storage;

	public DocumentOcrTemplate(
			ImageAnnotatorClient imageAnnotatorClient,
			Storage storage) {
		this.imageAnnotatorClient = imageAnnotatorClient;
		this.storage = storage;
	}

	/**
	 * Runs OCR processing for a specified {@code document} and generates OCR output files
	 * under the path specified by {@code outputFilePathPrefix}. One JSON output file is
	 * produced for each page of the document.
	 *
	 * <p>
	 * For example, if you specify an {@code outputFilePathPrefix} of
	 * "gs://bucket_name/ocr_results/myDoc_", all the output files of OCR processing will be
	 * saved under prefix, such as:
	 *
	 * <ul>
	 * <li>gs://bucket_name/ocr_results/myDoc_output-1-to-1.json
	 * <li>gs://bucket_name/ocr_results/myDoc_output-2-to-2.json
	 * <li>gs://bucket_name/ocr_results/myDoc_output-3-to-3.json
	 * </ul>
	 *
	 * <p>
	 * Note: OCR processing operations may take several minutes to complete, so it may not be
	 * advisable to block on the completion of the operation. One may use the returned
	 * {@link ListenableFuture} to register callbacks or track the status of the operation.
	 *
	 * @param document The {@link GoogleStorageLocation} of the document to run OCR processing
	 * @param outputFilePathPrefix The {@link GoogleStorageLocation} of a file, folder, or a
	 *     bucket describing the path for which all output files shall be saved under
	 *
	 * @return A {@link ListenableFuture} allowing you to register callbacks or wait for the
	 * completion of the operation.
	 */
	public ListenableFuture<DocumentOcrResultSet> runOcrForDocument(
			GoogleStorageLocation document,
			GoogleStorageLocation outputFilePathPrefix) {
		if (!document.isFile()) {
			throw new IllegalArgumentException(
					"Provided document location is not a valid file location: " + document);
		}

		GcsSource gcsSource = GcsSource.newBuilder()
				.setUri(document.uriString())
				.build();

		Blob documentBlob = this.storage.get(
				BlobId.of(document.getBucketName(), document.getBlobName()));
		if (documentBlob == null) {
			throw new IllegalArgumentException(
					"Provided document does not exist: " + document);
		}

		String contentType = documentBlob.getContentType();
		InputConfig inputConfig = InputConfig.newBuilder()
				.setMimeType(contentType)
				.setGcsSource(gcsSource)
				.build();

		GcsDestination gcsDestination = GcsDestination.newBuilder()
				.setUri(outputFilePathPrefix.uriString())
				.build();

		OutputConfig outputConfig = OutputConfig.newBuilder()
				.setGcsDestination(gcsDestination)
				.setBatchSize(1)
				.build();

		Feature feature = Feature.newBuilder()
				.setType(Type.DOCUMENT_TEXT_DETECTION)
				.build();

		AsyncAnnotateFileRequest request = AsyncAnnotateFileRequest.newBuilder()
				.addFeatures(feature)
				.setInputConfig(inputConfig)
				.setOutputConfig(outputConfig)
				.build();

		OperationFuture<AsyncBatchAnnotateFilesResponse, OperationMetadata> result =
				imageAnnotatorClient.asyncBatchAnnotateFilesAsync(Collections.singletonList(request));

		return extractOcrResultFuture(result);
	}

	/**
	 * Parses the OCR output files with the specified {@code jsonFilesetPrefix}. This method
	 * assumes that all of the OCR output files with the prefix are a part of the same
	 * document. This method is useful for processing a collection of output files produced by
	 * the same document.
	 *
	 * @param jsonFilePathPrefix the folder location containing all of the JSON files of OCR
	 *     output
	 * @return A {@link DocumentOcrResultSet} describing the OCR content of a document
	 */
	public DocumentOcrResultSet parseOcrOutputFileSet(GoogleStorageLocation jsonFilePathPrefix) {
		String nonNullPrefix = (jsonFilePathPrefix.getBlobName() == null) ? "" : jsonFilePathPrefix.getBlobName();

		Page<Blob> blobsInFolder = this.storage.list(
				jsonFilePathPrefix.getBucketName(),
				BlobListOption.currentDirectory(),
				BlobListOption.prefix(nonNullPrefix));

		List<Blob> blobPages =
				StreamSupport.stream(blobsInFolder.getValues().spliterator(), false)
						.filter(blob -> blob.getContentType().equals("application/octet-stream"))
						.sorted(Comparator.comparingInt(blob -> extractPageNumber(blob)))
						.collect(Collectors.toList());

		return new DocumentOcrResultSet(blobPages, this.storage);
	}

	/**
	 * Parses a single JSON output file and returns the parsed OCR content.
	 *
	 * @param jsonFile the location of the JSON output file
	 * @return the {@link TextAnnotation} containing the OCR results
	 * @throws InvalidProtocolBufferException if the JSON file cannot be deserialized into a
	 *     {@link TextAnnotation} object
	 */
	public TextAnnotation parseOcrOutputFile(GoogleStorageLocation jsonFile)
			throws InvalidProtocolBufferException {
		if (!jsonFile.isFile()) {
			throw new IllegalArgumentException(
					"Provided jsonOutputFile location is not a valid file location: " + jsonFile);
		}

		Blob jsonOutputBlob = this.storage.get(
				BlobId.of(jsonFile.getBucketName(), jsonFile.getBlobName()));
		return parseJsonBlob(jsonOutputBlob);
	}

	static TextAnnotation parseJsonBlob(Blob blob) throws InvalidProtocolBufferException {
		AnnotateFileResponse.Builder annotateFileResponseBuilder = AnnotateFileResponse.newBuilder();
		String jsonContent = new String(blob.getContent());
		JsonFormat.parser().merge(jsonContent, annotateFileResponseBuilder);

		AnnotateFileResponse annotateFileResponse = annotateFileResponseBuilder.build();

		return annotateFileResponse.getResponses(0).getFullTextAnnotation();
	}

	private ListenableFuture<DocumentOcrResultSet> extractOcrResultFuture(
			OperationFuture<AsyncBatchAnnotateFilesResponse, OperationMetadata> grpcFuture) {

		SettableListenableFuture<DocumentOcrResultSet> result = new SettableListenableFuture<>();

		ApiFutures.addCallback(grpcFuture, new ApiFutureCallback<AsyncBatchAnnotateFilesResponse>() {
			@Override
			public void onFailure(Throwable throwable) {
				result.setException(throwable);
			}

			@Override
			public void onSuccess(
					AsyncBatchAnnotateFilesResponse asyncBatchAnnotateFilesResponse) {

				String outputFolderUri = asyncBatchAnnotateFilesResponse.getResponsesList().get(0)
						.getOutputConfig()
						.getGcsDestination()
						.getUri();

				GoogleStorageLocation outputFolderLocation = new GoogleStorageLocation(outputFolderUri);
				result.set(parseOcrOutputFileSet(outputFolderLocation));
			}
		}, Runnable::run);

		return result;
	}

	private static int extractPageNumber(Blob blob) {
		Matcher matcher = OUTPUT_PAGE_PATTERN.matcher(blob.getName());
		boolean success = matcher.find();

		if (success) {
			return Integer.parseInt(matcher.group(1));
		}
		else {
			return -1;
		}
	}
}