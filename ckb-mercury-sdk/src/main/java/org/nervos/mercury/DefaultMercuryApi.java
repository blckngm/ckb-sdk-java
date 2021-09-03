package org.nervos.mercury;

import static java.util.stream.Collectors.toList;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.nervos.ckb.service.RpcService;
import org.nervos.ckb.utils.AmountUtils;
import org.nervos.mercury.model.GetBalancePayloadBuilder;
import org.nervos.mercury.model.TransferPayloadBuilder;
import org.nervos.mercury.model.req.*;
import org.nervos.mercury.model.resp.*;

public class DefaultMercuryApi implements MercuryApi {

  private RpcService rpcService;
  private Gson g =
      new GsonBuilder()
          .registerTypeAdapter(QueryAddress.class, new KeyAddress(""))
          .registerTypeAdapter(QueryAddress.class, new NormalAddress(""))
          .registerTypeAdapter(RecordResponse.class, new RecordResponse())
          .create();

  public DefaultMercuryApi(String mercuryUrl, boolean isDebug) {
    this.rpcService = new RpcService(mercuryUrl, isDebug);
  }

  public DefaultMercuryApi(RpcService rpcService) {
    this.rpcService = rpcService;
  }

  @Override
  public GetBalanceResponse getBalance(GetBalancePayload payload) throws IOException {

    GetBalanceResponse.RpcGetBalanceResponse resp =
        this.rpcService.post(
            RpcMethods.GET_BALANCE,
            Arrays.asList(payload),
            GetBalanceResponse.RpcGetBalanceResponse.class,
            g);

    GetBalanceResponse result = new GetBalanceResponse();
    result.balances =
        resp.balances
            .stream()
            .map(
                x -> {
                  BalanceResponse balance = new BalanceResponse();
                  balance.address = x.keyAddress;
                  balance.free = x.unconstrained;
                  balance.claimable = x.fleeting;
                  balance.freezed = x.locked;
                  if (Objects.isNull(x.udtHash) || x.udtHash == "") {
                    balance.assetInfo = AssetInfo.newCkbAsset();
                  } else {
                    balance.assetInfo = AssetInfo.newUdtAsset(x.udtHash);
                  }

                  return balance;
                })
            .collect(toList());

    return result;
  }

  @Override
  public TransactionCompletionResponse buildTransferTransaction(TransferPayload payload)
      throws IOException {
    List<TransferItem> transferItems =
        payload
            .items
            .stream()
            .filter(x -> Objects.equals(x.to.getClass(), ToKeyAddress.class))
            .collect(toList());
    if (transferItems.size() > 0) {
      if (payload.items.stream().anyMatch(item -> !item.to.isPayByFrom())
          && (payload.udtHash == null || payload.udtHash == "")) {
        throw new RuntimeException("The transaction does not support ckb");
      }
    }

    return this.rpcService.post(
        RpcMethods.BUILD_TRANSFER_TRANSACTION,
        Arrays.asList(payload),
        TransactionCompletionResponse.class);
  }

  @Override
  public TransactionCompletionResponse buildAdjustAccountTransaction(AdjustAccountPayload payload)
      throws IOException {
    return this.rpcService.post(
        RpcMethods.BUILD_ASSET_ACCOUNT_CREATION_TRANSACTION,
        Arrays.asList(payload),
        TransactionCompletionResponse.class);
  }

  @Override
  public TransactionCompletionResponse buildSmartTransferTransaction(SmartTransferPayload payload)
      throws IOException {
    return this.buildTransferTransaction(toTransferPayload(payload));
  }

  @Override
  public GenericTransactionWithStatusResponse getGenericTransaction(String txHash)
      throws IOException {
    return this.rpcService.post(
        RpcMethods.GET_GENERIC_TRANSACTION,
        Arrays.asList(txHash),
        GenericTransactionWithStatusResponse.class,
        g);
  }

  @Override
  public GenericBlockResponse getGenericBlock(GetGenericBlockPayload payload) throws IOException {
    return this.rpcService.post(
        RpcMethods.GET_GENERIC_BLOCK, Arrays.asList(payload), GenericBlockResponse.class, g);
  }

  @Override
  public List<String> registerAddresses(List<String> normalAddresses) throws IOException {
    return this.rpcService.post(
        RpcMethods.REGISTER_ADDRESSES,
        Arrays.asList(normalAddresses),
        new TypeToken<List<String>>() {}.getType());
  }

  @Override
  public TransactionCompletionResponse buildAssetCollectionTransaction(CollectAssetPayload payload)
      throws IOException {
    return this.rpcService.post(
        RpcMethods.BUILD_ASSET_COLLECTION_TRANSACTION,
        Arrays.asList(payload),
        TransactionCompletionResponse.class);
  }

  @Override
  public QueryGenericTransactionsResponse queryGenericTransactions(
      QueryGenericTransactionsPayload payload) throws IOException {
    return this.rpcService.post(
        RpcMethods.QUERY_GENERIC_TRANSACTIONS,
        Arrays.asList(payload),
        QueryGenericTransactionsResponse.class);
  }

  @Override
  public Integer getAccountNumber(String address) throws IOException {
    return this.rpcService.post(
        RpcMethods.GET_ACCOUNT_NUMBER, Arrays.asList(address), Integer.class);
  }

  private TransferPayload toTransferPayload(SmartTransferPayload payload) throws IOException {

    List<GetBalanceResponse> fromBalances = this.getBalance(payload.from, payload.assetInfo);

    checkFeePay(fromBalances, payload.to);
    Source source = getSource(payload.assetInfo, fromBalances, payload.to);

    TransferPayloadBuilder builder = new TransferPayloadBuilder();
    builder.from(new FromKeyAddresses(payload.from.stream().collect(Collectors.toSet()), source));
    for (SmartTo to : payload.to) {
      if (this.getAccountNumber(to.address).compareTo(0) > 0) {
        builder.addItem(new ToKeyAddress(to.address, Action.pay_by_to), to.amount);
      } else {
        builder.addItem(new ToKeyAddress(to.address, Action.pay_by_from), to.amount);
      }
    }

    if (Objects.equals(payload.assetInfo.assetType, AssetInfo.AssetType.UDT)) {
      builder.udtHash(payload.assetInfo.udtHash);
    }
    return builder.build();
  }

  private Source getSource(
      AssetInfo assetInfo, List<GetBalanceResponse> fromBalances, List<SmartTo> to) {
    BigInteger fromBalance = this.getBalance(fromBalances, assetInfo.assetType, "claimable");
    BigInteger totalAmount =
        to.stream().map(x -> x.amount).reduce(BigInteger.ZERO, (x, y) -> x.add(y));

    if (fromBalance.compareTo(totalAmount) >= 0) {
      return Source.fleeting;
    } else {
      return Source.unconstrained;
    }
  }

  private void checkFeePay(List<GetBalanceResponse> fromBalances, List<SmartTo> to) {
    BigInteger from = this.getBalance(fromBalances, AssetInfo.AssetType.CKB, "free");
    BigInteger totalAmount =
        to.stream().map(x -> x.amount).reduce(BigInteger.ZERO, (x, y) -> x.add(y));

    BigInteger feeThreshold = AmountUtils.ckbToShannon(0.0001);
    if (from.add(feeThreshold).compareTo(totalAmount) < 0) {
      throw new RuntimeException("CKB Insufficient balance to pay the fee");
    }
  }

  private BigInteger getBalance(
      List<GetBalanceResponse> fromBalances, AssetInfo.AssetType assetType, String balanceType) {
    return fromBalances
        .stream()
        .map(
            x -> {
              BalanceResponse balanceResponse =
                  x.balances
                      .stream()
                      .filter(y -> Objects.equals(y.assetInfo.assetType, assetType))
                      .findAny()
                      .get();

              if (Objects.equals(balanceType, "free")) {
                return balanceResponse.free;
              }

              if (Objects.equals(balanceType, "claimable")) {
                return balanceResponse.claimable;
              }

              if (Objects.equals(balanceType, "freezed")) {
                return balanceResponse.freezed;
              }
              return BigInteger.ZERO;
            })
        .reduce(BigInteger.ZERO, (x, y) -> x.add(y));
  }

  private List<GetBalanceResponse> getBalance(List<String> addresses, AssetInfo udtAsset)
      throws IOException {
    List<GetBalanceResponse> result = new ArrayList<>(addresses.size());
    for (String address : addresses) {
      GetBalancePayloadBuilder builder = new GetBalancePayloadBuilder();
      builder.address(address);
      builder.addAssetInfo(AssetInfo.newCkbAsset());
      if (Objects.nonNull(udtAsset)) {
        builder.addAssetInfo(udtAsset);
      }

      GetBalanceResponse balance = this.getBalance(builder.build());
      result.add(balance);
    }

    return result;
  }
}
