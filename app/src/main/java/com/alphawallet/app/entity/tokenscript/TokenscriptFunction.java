package com.alphawallet.app.entity.tokenscript;

import io.reactivex.Observable;
import com.alphawallet.token.entity.*;
import com.alphawallet.token.tools.TokenDefinition;
import okhttp3.OkHttpClient;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction;

/**
 * Created by James on 13/06/2019.
 * Stormbird in Sydney
 */
public abstract class TokenscriptFunction
{
    public Function generateTransactionFunction(String walletAddress, BigInteger tokenId, TokenDefinition definition, FunctionDefinition function, AttributeInterface attrIf)
    {
        //pre-parse tokenId.
        if (tokenId.bitCount() > 256) tokenId = tokenId.or(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)); //truncate tokenId too large
        if (walletAddress == null) walletAddress = ZERO_ADDRESS;

        List<Type> params = new ArrayList<Type>();
        List<TypeReference<?>> returnTypes = new ArrayList<TypeReference<?>>();
        for (MethodArg arg : function.parameters)
        {
            resolveReference(walletAddress, arg, tokenId, definition, attrIf);
            switch (arg.parameterType)
            {
                case "uint256":
                    switch (arg.element.ref)
                    {
                        case "tokenId":
                            params.add(new Uint256(tokenId));
                            break;
                        case "value":
                        default:
                            params.add(new Uint256(new BigInteger(arg.element.value)));
                            break;
                    }
                    break;
                case "address":
                    switch (arg.element.ref)
                    {
                        case "ownerAddress":
                            params.add(new Address(walletAddress));
                            break;
                        case "value":
                        default:
                            params.add(new Address(arg.element.value));
                            break;
                    }
                    break;
                default:
                    System.out.println("NOT IMPLEMENTED: " + arg.parameterType);
                    break;
            }
        }
        switch (function.syntax)
        {
            case Boolean:
            case Integer:
            case NumericString:
                returnTypes.add(new TypeReference<Uint256>()
                {
                });
                break;
            case IA5String:
            case DirectoryString:
                returnTypes.add(new TypeReference<Utf8String>()
                {
                });
                break;
        }

        return new Function(function.method,
                            params, returnTypes);
    }

    private void handleTransactionResult(TransactionResult result, Function function, String responseValue, FunctionDefinition fd)
    {
        try
        {
            long currentTime = System.currentTimeMillis();
            //try to interpret the value
            List<Type> response = FunctionReturnDecoder.decode(responseValue, function.getOutputParameters());
            result.resultTime = currentTime;
            if (response.size() > 0)
            {
                Type val = response.get(0);
                switch (fd.syntax)
                {
                    case Boolean:
                        BigDecimal value = new BigDecimal(((Uint256) val).getValue());
                        result.result = value.equals(BigDecimal.ZERO) ? "FALSE" : "TRUE";
                        break;
                    case Integer:
                    case NumericString:
                        result.result = new BigDecimal(((Uint256) val).getValue()).toString();
                        break;
                    case IA5String:
                    case DirectoryString:
                        result.result = (String) response.get(0).getValue();
                        if (responseValue.length() > 2 && result.result.length() == 0)
                        {
                            result.result = checkBytesString(responseValue);
                        }
                        break;
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private String checkBytesString(String responseValue) throws Exception
    {
        String name = "";
        if (responseValue.length() > 0)
        {
            //try raw bytes
            byte[] data = Numeric.hexStringToByteArray(responseValue);
            //check leading bytes for non-zero
            if (data[0] != 0)
            {
                //truncate zeros
                int index = data.length - 1;
                while (data[index] == 0 && index > 0)
                    index--;
                if (index != (data.length - 1))
                {
                    data = Arrays.copyOfRange(data, 0, index + 1);
                }
                name = new String(data, "UTF-8");
            }
        }

        return name;
    }

    public TokenScriptResult.Attribute parseFunctionResult(TransactionResult transactionResult, AttributeType attr)
    {
        String res = attr.getSyntaxVal(transactionResult.result);
        BigInteger val = transactionResult.tokenId; //?

        if (attr.syntax == TokenDefinition.Syntax.NumericString)
        {
            if (transactionResult.result == null)
            {
                res = "0";
            }
            else if (transactionResult.result.startsWith("0x"))
            {
                res = res.substring(2);
            }
            val = new BigInteger(res, 16);
        }
        return new TokenScriptResult.Attribute(attr.id, attr.name, val, res);
    }

    public static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    /**
     * Haven't pre-cached this value yet, so need to fetch it before we can proceed
     * @param override
     * @param attr
     * @param tokenId
     * @param definition
     * @return
     */
    public Observable<TransactionResult> fetchResultFromEthereum(String walletAddress, ContractAddress override, AttributeType attr, BigInteger tokenId, TokenDefinition definition, AttributeInterface attrIf)
    {
        return Observable.fromCallable(() -> {
            ContractAddress useAddress;
            if (override == null) // contract not specified - is not holder contract
            {
                //determine address using definition context
                useAddress = new ContractAddress(attr.function, definition.context.cAddr.chainId, definition.context.cAddr.address);
            }
            else
            {
                useAddress = override;
            }
            TransactionResult transactionResult = new TransactionResult(useAddress.chainId, useAddress.address, tokenId, attr);
            // 1: create transaction call
            org.web3j.abi.datatypes.Function transaction = generateTransactionFunction(walletAddress, tokenId, definition, attr.function, attrIf);
            // 2: create web3 connection
            OkHttpClient okClient = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build();

            HttpService nodeService = new HttpService(MagicLinkInfo.getNodeURLByNetworkId(useAddress.chainId), okClient, false);

            Web3j web3j = Web3j.build(nodeService);

            //now push the transaction
            String result = callSmartContractFunction(web3j, transaction, useAddress.address, ZERO_ADDRESS);

            handleTransactionResult(transactionResult, transaction, result, attr.function);
            return transactionResult;
        });
    }

    private String callSmartContractFunction(Web3j web3j,
                                             Function function, String contractAddress, String walletAddr) throws Exception {
        String encodedFunction = FunctionEncoder.encode(function);

        try
        {
            org.web3j.protocol.core.methods.request.Transaction transaction
                    = createEthCallTransaction(walletAddr, contractAddress, encodedFunction);
            EthCall response = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();

            return response.getValue();
        }
        catch (IOException e)
        {
            //Connection error. Use cached value
            return null;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    private void resolveReference(String walletAddress, MethodArg arg, BigInteger tokenId, TokenDefinition definition, AttributeInterface attrIf)
    {
        if (definition != null && definition.attributeTypes.containsKey(arg.element.ref))
        {
            arg.element.value = fetchAttrResult(walletAddress, arg.element.ref, tokenId, null, definition, attrIf, 0).blockingSingle().text;
        }
    }

    public Observable<TokenScriptResult.Attribute> fetchAttrResult(String walletAddress, String attribute, BigInteger tokenId, ContractAddress cAddr, TokenDefinition td, AttributeInterface attrIf, long transactionUpdate)
    {
        AttributeType attr = td.attributeTypes.get(attribute);
        if (attr == null || isAttrIncomplete(attr)) return Observable.fromCallable(() -> new TokenScriptResult.Attribute("bd", "bd", BigInteger.ZERO, ""));
        if (attr.function == null)  // static attribute from tokenId (eg city mapping from tokenId)
        {
            return staticAttribute(attr, tokenId);
        }
        else
        {
            ContractAddress useAddress;
            if (cAddr == null) useAddress = new ContractAddress(attr.function);
            else useAddress = new ContractAddress(attr.function, cAddr.chainId, cAddr.address);
            TransactionResult transactionResult = attrIf.getFunctionResult(useAddress, attr, tokenId); //Needs to allow for multiple tokenIds
            if (attrIf.resolveOptimisedAttr(useAddress, attr, transactionResult) || !transactionResult.needsUpdating(transactionUpdate)) //can we use wallet's known data or cached value?
            {
                return resultFromDatabase(transactionResult, attr);
            }
            else  //if value is old or there wasn't any previous value
            {
                //for function query, never need wallet address
                return fetchResultFromEthereum(walletAddress, useAddress, attr, tokenId, td, attrIf)       // Fetch function result from blockchain
                        .map(result -> restoreFromDBIfRequired(result, transactionResult))  // If network unavailable restore value from cache
                        .map(attrIf::storeAuxData)                                          // store new data
                        .map(result -> parseFunctionResult(result, attr));    // write returned data into attribute
            }
        }
    }

    private boolean isAttrIncomplete(AttributeType attr)
    {
        if (attr.function == null) return false;

        for (MethodArg arg : attr.function.parameters)
        {
            int index = arg.getTokenIndex();
            if (arg.isTokenId() && index >= 0 && (arg.element.value == null || arg.element.value.length() == 0))
            {
                return true;
            }
        }

        return false;
    }

    public Observable<TokenScriptResult.Attribute> resolveAttributes(String walletAddress, BigInteger tokenId, AttributeInterface attrIf, ContractAddress cAddr, TokenDefinition td, long transactionUpdate)
    {
        td.context = new TokenscriptContext();
        td.context.cAddr = cAddr;
        td.context.attrInterface = attrIf;

        return Observable.fromIterable(new ArrayList<>(td.attributeTypes.values()))
                .flatMap(attr -> fetchAttrResult(walletAddress, attr.id, tokenId, cAddr, td, attrIf, transactionUpdate));
    }

    private Observable<TokenScriptResult.Attribute> staticAttribute(AttributeType attr, BigInteger tokenId)
    {
        return Observable.fromCallable(() -> {
            try
            {
                BigInteger val = tokenId.and(attr.bitmask).shiftRight(attr.bitshift);
                return new TokenScriptResult.Attribute(attr.id, attr.name, val, attr.getSyntaxVal(attr.toString(val)));
            }
            catch (Exception e)
            {
                return new TokenScriptResult.Attribute(attr.id, attr.name, tokenId, "unsupported encoding");
            }
        });
    }

    private Observable<TokenScriptResult.Attribute> resultFromDatabase(TransactionResult transactionResult, AttributeType attr)
    {
        //return Observable.fromCallable(() -> attr.function.parseFunctionResult(transactionResult, attr));
        return Observable.fromCallable(() -> parseFunctionResult(transactionResult, attr));
    }

    /**
     * Restore result from Database if required (eg connection failure), and if there was a database value to restore
     * @param result
     * @param transactionResult
     * @return
     */
    private TransactionResult restoreFromDBIfRequired(TransactionResult result, TransactionResult transactionResult)
    {
        if (result.resultTime == 0 && transactionResult != null)
        {
            result.result = transactionResult.result;
            result.resultTime = transactionResult.resultTime;
        }

        return result;
    }
}
