package org.opendaylight.distributed.tx.it.provider.datawriter;

import com.google.common.base.Function;
import com.google.common.util.concurrent.*;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.distributed.tx.api.DTXLogicalTXProviderType;
import org.opendaylight.distributed.tx.api.DTx;
import org.opendaylight.distributed.tx.api.DTxException;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.distributed.tx.it.model.rev150105.BenchmarkTestInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.distributed.tx.it.model.rev150105.DatastoreTestData;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.distributed.tx.it.model.rev150105.datastore.test.data.OuterList;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.distributed.tx.it.model.rev150105.datastore.test.data.OuterListKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.distributed.tx.it.model.rev150105.datastore.test.data.outer.list.InnerList;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by sunny on 16-2-25.
 */
public class DtxAsyncWrite extends AbstractDataStoreWriter {
    private DTx dtx;
    private static final Logger LOG = LoggerFactory.getLogger(DtxAsyncWrite.class);

    public DtxAsyncWrite(BenchmarkTestInput input, DTx dtx, int outerElements, int innerElements)
    {
        super(input,outerElements,innerElements);
        this.dtx = dtx;
    }
    @Override
    public ListenableFuture<Void> writeData() {
        final SettableFuture<Void> setFuture = SettableFuture.create();
        long putsPerTx = input.getPutsPerTx();

        List<List<InnerList>> innerLists = buildInnerLists();
        //when the operation is delete we should build the test data first
        if (input.getOperation() == BenchmarkTestInput.Operation.DELETE)
        {
            boolean buildTestData = build();//build the test data for the operation
            if (!buildTestData)
            {
                setFuture.setException(new Throwable("can't build the test data for the delete operation"));
                return setFuture;
            }
        }

        InstanceIdentifier<DatastoreTestData> nodeId = InstanceIdentifier.create(DatastoreTestData.class);
        //store all the put futures
        List<ListenableFuture<Void>> putFutures = new ArrayList<ListenableFuture<Void>>((int) putsPerTx);
        startTime = System.nanoTime();

        int counter = 0;
        for (int i = 0; i < outerElements ; i++) {
            for (InnerList innerList : innerLists.get(i)) {
                InstanceIdentifier<InnerList> innerIid = InstanceIdentifier.create(DatastoreTestData.class)
                        .child(OuterList.class, new OuterListKey(i))
                        .child(InnerList.class, innerList.getKey());

                CheckedFuture<Void, DTxException> tx;
                if (input.getOperation() == BenchmarkTestInput.Operation.PUT) {
                    tx = dtx.putAndRollbackOnFailure(DTXLogicalTXProviderType.DATASTORE_TX_PROVIDER, LogicalDatastoreType.CONFIGURATION, innerIid, innerList, nodeId);
                }else if (input.getOperation() == BenchmarkTestInput.Operation.MERGE){
                    tx = dtx.mergeAndRollbackOnFailure(DTXLogicalTXProviderType.DATASTORE_TX_PROVIDER, LogicalDatastoreType.CONFIGURATION, innerIid, innerList, nodeId);
                }else{
                    tx = dtx.deleteAndRollbackOnFailure(DTXLogicalTXProviderType.DATASTORE_TX_PROVIDER, LogicalDatastoreType.CONFIGURATION, innerIid, nodeId);
                }

                putFutures.add(tx);
                counter++;

                if (counter == putsPerTx)
                {
                    //aggregate all the put futures into a listenable future, this future can make sure all the Async write has finish cache the data
                    ListenableFuture<Void> aggregatePutFuture = Futures.transform(Futures.allAsList(putFutures), new Function<List<Void>, Void>() {
                        @Nullable
                        @Override
                        public Void apply(@Nullable List<Void> voids) {
                            return null;
                        }
                    });

                    Futures.addCallback(aggregatePutFuture, new FutureCallback<Void>() {
                        @Override
                        public void onSuccess(@Nullable Void aVoid) {
                            CheckedFuture<Void, TransactionCommitFailedException> submitFuture = dtx.submit();
                            Futures.addCallback(submitFuture, new perSubmitFutureCallback(setFuture));
                        }

                        @Override
                        public void onFailure(Throwable throwable) {
                              setFuture.setException(throwable);
                        }
                    });
                    counter = 0;
                    putFutures = new ArrayList<ListenableFuture<Void>>((int) putsPerTx);
                }
            }
        }
        ListenableFuture<Void> aggregatePutFuture = Futures.transform(Futures.allAsList(putFutures), new Function<List<Void>, Void>() {
            @Nullable
            @Override
            public Void apply(@Nullable List<Void> voids) {
                return null;
            }
        });

        Futures.addCallback(aggregatePutFuture, new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void aVoid) {
                Futures.addCallback(dtx.submit(), new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void aVoid) {
                        LOG.info("Successfully asyncput all the data via the dtx");
                        endTime = System.nanoTime();
                        setFuture.set(null);
                        LOG.info("Transactions: submitted {}, completed {}", doSubmit, (txOk + txError));
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                           setFuture.setException(throwable);
                    }
                });
            }

            @Override
            public void onFailure(Throwable throwable) {
                setFuture.setException(throwable);
            }
        });

        return setFuture;
    }

}